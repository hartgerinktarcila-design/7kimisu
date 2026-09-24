//! 跨平台的**原子写**小工具（`tmp + sync_all + rename`）。
//!
//! 为什么单独一个模块：本仓"正确写法"散落在多处（`module_config.rs::save_config`、
//! `webadmin.rs::Config::save`、`profile.rs::atomic_write_text`、`feature.rs::save_binary_config`），
//! 都是同一个套路。v2.18 把需要它的新调用点（`webadmin_ksud::set_stealth_code` 写拨号密令）
//! 统一到一个**能单测**的实现上。
//!
//! 为什么不能放在 `utils.rs`：那个模块是 `#[cfg(target_os = "android")]` 的，
//! 放在那里宿主上根本编不进来 ⇒ 这条判据没法在 Mac 上回归（v2.17 的教训：
//! "没办法复现的修复等于没修"）。
//!
//! 判据（见本文件末尾单测）：**必须换 inode** —— 原地截断（`std::fs::write`）会保留
//! 同一个 inode，掉电/被杀时留下空或半截文件；`rename` 顶替则对外要么完整旧、要么完整新。

use std::path::Path;

/// 原子写一个小文件：同目录临时文件 →（可选）**先**收紧权限 → 写入 → `sync_all` → `rename`。
///
/// 🔴（v2.18）目前给 `webadmin_ksud::set_stealth_code`（拨号密令
/// `/data/adb/sevenk/stealth_code`）用。为什么必须原子：`std::fs::write` **先把目标截断**，
/// 掉电 / 被 kill / ENOSPC 时留下的是**空或半截**文件 —— 密令文件坏了 = 用户
/// **退不出隐身**（保命入口失效）。
///
/// 另外：权限必须在**写内容之前**用 `File::set_permissions` 就位。旧代码是
/// `File::create` 之后才 `set_permissions(0600)`，而 `init_event.rs` 里 `umask(0)`
/// ⇒ 存在"密令已经落盘、但同机可读"的窗口。
///
/// # Errors
/// 建目录 / 建临时文件 / 写 / `sync_all` / `rename` 任一步失败都返回 `Err`，
/// 并且**清掉临时文件**（不给下次留垃圾）。
pub fn write_atomic(path: &Path, bytes: &[u8], mode: Option<u32>) -> std::io::Result<()> {
    let Some(dir) = path.parent() else {
        return Err(std::io::Error::other("path has no parent directory"));
    };
    std::fs::create_dir_all(dir)?;
    let base = path
        .file_name()
        .map_or_else(|| "atomic".to_string(), |n| n.to_string_lossy().into_owned());
    // 临时名带随机后缀：调用方可能是"每连接一个线程"的服务，固定名会让并发写互相截断
    let uniq = crate::webadmin::random_hex(8).unwrap_or_else(|| std::process::id().to_string());
    let tmp = dir.join(temp_name_for(&base, std::process::id(), &uniq));

    let write = (|| -> std::io::Result<()> {
        use std::io::Write as _;
        let mut f = std::fs::File::create(&tmp)?;
        #[cfg(unix)]
        if let Some(m) = mode {
            use std::os::unix::fs::PermissionsExt;
            // 权限在写内容**之前**就位：内容一旦落盘就已经是收紧后的权限
            f.set_permissions(std::fs::Permissions::from_mode(m))?;
        }
        #[cfg(not(unix))]
        let _ = mode;
        f.write_all(bytes)?;
        f.sync_all()?;
        Ok(())
    })();
    if let Err(e) = write {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    Ok(())
}

/// [write_atomic] 用的临时文件**名字形状**：`.<原名>.tmp-<pid>-<uniq>`。
fn temp_name_for(base: &str, pid: u32, uniq: &str) -> String {
    format!(".{base}.tmp-{pid}-{uniq}")
}

/// 判断一个目录项名字是不是 [write_atomic] 留下的临时文件（`.<原名>.tmp-<pid>-<uniq>`）。
///
/// 为什么读侧需要它（🟡，v2.19）：`profile.rs::list_templates()` 会把目录里
/// **每一个**条目当模板打印出去，`apply_sepolies()` 会把每一个文件当策略应用。
/// 一次硬杀（`kill -9` / 掉电）正好落在 create 与 rename 之间时，就会留下一个
/// 临时文件 —— 它会变成界面上的「幽灵模板」，或者被当成**半截策略**应用进内核。
/// 读侧按这个名字形状过滤掉即可（写侧的成功/失败路径本来都会清掉它）。
///
/// 用 `rfind` 而不是 `find`：模板 id 本身允许含 `.`（`validate_name` 只放行
/// `[A-Za-z0-9._-]`），所以 `com.foo.tmp-1` 这种名字也可能存在 ——
/// 真正的临时后缀永远是**最后**那一截。
pub fn is_temp_name(name: &str) -> bool {
    let Some(rest) = name.strip_prefix('.') else {
        return false;
    };
    let Some(idx) = rest.rfind(".tmp-") else {
        return false;
    };
    let tail = &rest[idx + ".tmp-".len()..];
    let Some((pid, uniq)) = tail.split_once('-') else {
        return false;
    };
    !pid.is_empty()
        && pid.bytes().all(|b| b.is_ascii_digit())
        && !uniq.is_empty()
        && uniq.bytes().all(|b| b.is_ascii_alphanumeric())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 🔴（v2.18）密令文件"原子写"的证据：**必须走 tmp+rename**（原地截断会保留同一个 inode）。
    ///
    /// 旧写法 `std::fs::write` 是先 `O_TRUNC` 再写 —— inode 不变，中途掉电/被杀就留下
    /// 空或半截的 `stealth_code`（用户退不出隐身）。这条判据用 inode 变化把两种写法分开：
    /// 走 `rename` 的必然换 inode；原地截断的 inode 相同 ⇒ 断言失败。
    #[cfg(unix)]
    #[test]
    fn write_atomic_replaces_by_rename_and_keeps_mode() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};

        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("stealth_code");
        std::fs::write(&p, b"0000").unwrap();
        let ino_before = std::fs::metadata(&p).unwrap().ino();

        write_atomic(&p, b"1234", Some(0o600)).unwrap();

        assert_eq!(std::fs::read(&p).unwrap(), b"1234", "内容必须完整写进去");
        let after = std::fs::metadata(&p).unwrap();
        assert_ne!(
            ino_before,
            after.ino(),
            "必须 tmp+rename；原地截断（std::fs::write）会保留同一个 inode"
        );
        assert_eq!(
            after.permissions().mode() & 0o777,
            0o600,
            "权限必须在写内容之前就收紧（umask(0) 下不能有可读窗口）"
        );

        // 不留临时文件
        let leftovers: Vec<String> = std::fs::read_dir(t.path())
            .unwrap()
            .filter_map(std::result::Result::ok)
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.contains(".tmp-"))
            .collect();
        assert!(leftovers.is_empty(), "残留临时文件: {leftovers:?}");
    }

    /// 目标不存在时也能建出来（父目录不存在会先建）
    #[test]
    fn write_atomic_creates_parent_and_file() {
        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("a/b/c.txt");
        write_atomic(&p, b"hello", None).unwrap();
        assert_eq!(std::fs::read(&p).unwrap(), b"hello");
    }

    /// 🟡（v2.19）读侧过滤临时文件：**我们自己造的名字必须被认出来**，
    /// 而正常的模板/包名（含 `.` 的、含 `tmp` 字样的）**不许误伤**。
    #[test]
    fn temp_name_shape_is_recognized_and_does_not_over_match() {
        // 写侧实际生成的名字（`temp_name_for` 与 `write_atomic` 共用同一个形状）
        assert!(is_temp_name(&temp_name_for("com.foo.bar", 4242, "a1b2c3d4")));
        assert!(is_temp_name(&temp_name_for("tpl1", 7, "deadbeef")));
        // pid-only 兜底（拿不到 urandom 时）
        assert!(is_temp_name(&temp_name_for("tpl1", 7, "7")));
        // base 自己就含 ".tmp-" 时，认出来的仍是**最后**那一截
        assert!(is_temp_name(&temp_name_for("com.x.tmp-9", 1, "ff")));

        // 不许误伤：正常的模板名 / 包名 / 普通文件
        for ok_name in [
            "com.foo.bar",
            "tpl1",
            "my.tmp",
            "tmp",
            ".hidden",
            "com.foo.bar.tmp",
            "a.tmp-b",
            "com.foo.bar.tmp-x-y",
            "",
        ] {
            assert!(
                !is_temp_name(ok_name),
                "{ok_name:?} 不该被当成临时文件（会从模板列表里被误删）"
            );
        }
    }

    /// 🔴（v2.19）"幽灵模板"复现：`kill -9` 落在 create 与 rename 之间的现场
    /// 就是"一个临时文件留在目录里"。用 `list_templates` 的判据（`is_temp_name`）
    /// 过滤后，模板列表里必须**只剩真模板**。
    #[test]
    fn ghost_tmp_is_filtered_out_of_a_template_listing() {
        let t = tempfile::tempdir().unwrap();
        // 真模板
        write_atomic(&t.path().join("tpl_real"), b"pkg=1", None).unwrap();
        // 模拟"写到一半被杀"留下的临时文件（写侧自己造的形状）
        let ghost = t.path().join(temp_name_for("tpl_real", 999, "abc123"));
        std::fs::write(&ghost, b"pkg=").unwrap();

        let listed: Vec<String> = std::fs::read_dir(t.path())
            .unwrap()
            .filter_map(std::result::Result::ok)
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| !is_temp_name(n))
            .collect();
        assert_eq!(listed, vec!["tpl_real".to_string()], "幽灵模板必须被过滤掉");
        assert!(ghost.exists(), "前提：这条断言测的是读侧过滤，不是写侧清理");
    }
}
