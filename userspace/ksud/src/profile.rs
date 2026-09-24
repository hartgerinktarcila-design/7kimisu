use crate::utils::ensure_dir_exists;
use crate::{defs, sepolicy};
use anyhow::{Context, Result, bail};
use std::path::Path;

/// 🟠5（v2.4）：校验"包名 / 模板 id"。
///
/// 为什么必须有这道闸：以前 `pkg` / `id` **零校验**就直接 `join()` ——
/// `ksud profile set-template ../../data/adb/sevenk/.allowlist x`
/// 能以 root 身份写到（`delete_template` 则是删掉）**任意文件**。
///
/// 两道闸：
///   1. 白名单字符 `[A-Za-z0-9._-]`（`/` 与 `\` 天然被拒，路径分隔符进不来）；
///   2. 拒绝 `.` / `..`，并用 `Path::file_name()` 二次确认"它就是个普通文件名"。
fn validate_name(kind: &str, name: &str) -> Result<()> {
    if name.is_empty() {
        bail!("{kind} must not be empty");
    }
    if name.len() > 128 {
        bail!("{kind} is too long ({} bytes)", name.len());
    }
    if !name
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
    {
        bail!("invalid {kind} '{name}': only [A-Za-z0-9._-] is allowed");
    }
    if name == "." || name == ".." {
        bail!("invalid {kind} '{name}'");
    }
    if Path::new(name).file_name().and_then(|n| n.to_str()) != Some(name) {
        bail!("invalid {kind} '{name}': not a plain file name");
    }
    Ok(())
}

/// 🟡5（v2.8 审计）／🟡（v2.19）：**原子写**一个小文本文件。
///
/// v2.8 起这里自己抄了一份 `temp + sync_all + rename`；v2.19 统一改成调用
/// `atomic::write_atomic` —— 同一个判据只有**一处实现、一处单测**，而且临时名带
/// pid + 随机后缀（固定名在并发写时会互相截断）。"一致性也是稳定性"。
///
/// 为什么必须原子：`std::fs::write` 是**先把目标文件截断**再写。写到一半掉电/被杀
/// （改 SELinux 策略、改模板都可能在重启前的窗口里发生）→ 留下的是一份**半截策略**：
///    · `set_sepolicy` 后面还要 `apply_file`，半截策略直接报错或（更糟）**写进内核一半**；
///    · 模板半截 = 用户自己编的模板悄悄坏掉，界面上还显示"保存成功"。
///
/// ⚠️ 旧临时名是把 `.tmp` **追加**在原名后面（`com.foo.bar.tmp`）。它落在模板目录里
/// 会被 `list_templates()` 当成一个**幽灵模板**显示出来，也会被 `apply_sepolies()`
/// 当成一份策略去应用。统一到 `write_atomic` 之后临时名是点开头的
/// `.<原名>.tmp-<pid>-<uniq>`，读侧再用 `atomic::is_temp_name` 过滤掉
/// （见本文件 `list_templates` / `apply_sepolies` 两处）。
fn atomic_write_text(dst: &Path, contents: String) -> Result<()> {
    // 与原来的 `std::fs::write` 一样：调用方给的就是一份 String，这里直接接手（`into_bytes`）
    let bytes = contents.into_bytes();
    crate::atomic::write_atomic(dst, &bytes, None)
        .with_context(|| format!("failed to atomically write {}", dst.display()))
}

pub fn set_sepolicy(pkg: String, policy: String) -> Result<()> {
    validate_name("package name", &pkg)?;
    ensure_dir_exists(defs::PROFILE_SELINUX_DIR)?;
    let policy_file = Path::new(defs::PROFILE_SELINUX_DIR).join(pkg);
    atomic_write_text(&policy_file, policy)?;
    sepolicy::apply_file(&policy_file)?;
    Ok(())
}

pub fn get_sepolicy(pkg: String) -> Result<()> {
    validate_name("package name", &pkg)?;
    let policy_file = Path::new(defs::PROFILE_SELINUX_DIR).join(pkg);
    let policy = std::fs::read_to_string(policy_file)?;
    println!("{policy}");
    Ok(())
}

// ksud doesn't guarteen the correctness of template, it just save
pub fn set_template(id: String, template: String) -> Result<()> {
    validate_name("template id", &id)?;
    ensure_dir_exists(defs::PROFILE_TEMPLATE_DIR)?;
    let template_file = Path::new(defs::PROFILE_TEMPLATE_DIR).join(id);
    atomic_write_text(&template_file, template)?;
    Ok(())
}

pub fn get_template(id: String) -> Result<()> {
    validate_name("template id", &id)?;
    let template_file = Path::new(defs::PROFILE_TEMPLATE_DIR).join(id);
    let template = std::fs::read_to_string(template_file)?;
    println!("{template}");
    Ok(())
}

pub fn delete_template(id: String) -> Result<()> {
    validate_name("template id", &id)?;
    let template_file = Path::new(defs::PROFILE_TEMPLATE_DIR).join(id);
    std::fs::remove_file(template_file)?;
    Ok(())
}

pub fn list_templates() -> Result<()> {
    let templates = std::fs::read_dir(defs::PROFILE_TEMPLATE_DIR);
    let Ok(templates) = templates else {
        return Ok(());
    };
    for template in templates {
        let template = template?;
        let template = template.file_name();
        if let Some(template) = template.to_str() {
            // 🟡（v2.19）跳过 `write_atomic` 可能残留的临时文件 ——
            // 否则一次硬杀正好落在 create 与 rename 之间，模板列表里就多一个"幽灵模板"。
            if crate::atomic::is_temp_name(template) {
                continue;
            }
            println!("{template}");
        }
    }
    Ok(())
}

pub fn apply_sepolies() -> Result<()> {
    let path = Path::new(defs::PROFILE_SELINUX_DIR);
    if !path.exists() {
        log::info!("profile sepolicy dir not exists.");
        return Ok(());
    }

    let sepolicies =
        std::fs::read_dir(path).with_context(|| "profile sepolicy dir open failed.".to_string())?;
    for sepolicy in sepolicies {
        let Ok(sepolicy) = sepolicy else {
            log::info!("profile sepolicy dir read failed.");
            continue;
        };
        let sepolicy = sepolicy.path();
        // 🟡（v2.19）绝不把 `write_atomic` 的临时文件当策略应用：
        // 那是**半截内容**，`sepolicy::apply_file` 可能读进去一半（比报错更糟）。
        if sepolicy
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(crate::atomic::is_temp_name)
        {
            log::info!("skip atomic temp file: {}", sepolicy.display());
            continue;
        }
        if sepolicy::apply_file(&sepolicy).is_ok() {
            log::info!("profile sepolicy applied: {}", sepolicy.display());
        } else {
            log::info!("profile sepolicy apply failed: {}", sepolicy.display());
        }
    }
    Ok(())
}
