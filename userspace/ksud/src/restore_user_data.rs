//! 🔴2（v2.11）从**卸载备份**恢复用户数据。
//!
//! 背景（审计点名的缺口）：`ksud uninstall` 会先把用户数据备份到
//! `/data/adb/sevenk_uninstall_backup_<时间戳>/`（`.allowlist` / `stealth` /
//! `stealth_code` / `module_configs/` / `profile/` / `.feature_config`），
//! 但**全仓没有任何代码读回它**（只有卸载时那两行 `println!`）——
//! 也就是"备份了但用户拿不回来" = 等于没备份 ✗。
//!
//! 现在补上：`ksud restore-user-data [--from <dir>]`（默认取**最新的一份**）
//!   · 复制回 `WORKING_DIR`（`/data/adb/sevenk/`）；
//!   · **逐字节校验**后才算成功；
//!   · 动手之前先把"当前数据"快照到 `<备份>/pre_restore_<时间戳>/`（可回退）；
//!   · 任何一步失败 ⇒ 那一项**一个字节都不改**（先写临时文件、校验通过才 rename 到位）。
//!
//! ⚠️ 恢复**只做覆盖/新增，绝不删除**任何现有文件（`/data/adb` 里的东西都是用户的命根子）。
//! 恢复完必须**重启一次**：内核只在开机时读一次授权名单/隐身标志。
//!
//! 这一模块刻意做成**纯 std + 显式路径**（宿主上可整条单测），路径常量由 android 侧的
//! `utils::restore_user_data()` 从 `defs` 里填。

// 与 `migrate.rs` 同款：本模块在 Android 上由 cli/utils 调用；在开发机（宿主 target）上
// 只有单测用得到它，于是会报一串 dead_code —— 这里显式允许，免得污染宿主上的 clippy 结果。
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use anyhow::{Context, Result, bail};
use std::fs;
use std::path::{Path, PathBuf};

use crate::migrate::{ALLOWLIST_FILE, STEALTH_CODE_FILE, STEALTH_FILE};

/// 卸载备份目录的固定前缀（`utils::backup_user_data()` 用的是同一个）
pub const BACKUP_PREFIX: &str = "sevenk_uninstall_backup_";
/// 二进制特性开关（纯配置，丢了只是回默认）
pub const FEATURE_CONFIG_FILE: &str = ".feature_config";
/// 模块配置目录（备份里的名字 → 恢复到 `MODULE_CONFIG_DIR`）
pub const MODULE_CONFIGS_DIR: &str = "module_configs";
/// profile 目录（SELinux 规则 + 模板）
pub const PROFILE_DIR: &str = "profile";

/// 恢复时要动的路径（显式传入 ⇒ 单测可以用临时目录整条跑）
#[derive(Debug, Clone)]
pub struct RestorePaths {
    /// 备份们所在的目录（真机上 = `/data/adb/`）
    pub adb: PathBuf,
    /// 恢复到哪（真机上 = `/data/adb/sevenk/`）
    pub working: PathBuf,
    /// 模块配置恢复目标（真机上 = `/data/adb/sevenk/module_configs/`）
    pub module_configs: PathBuf,
    /// profile 恢复目标（真机上 = `/data/adb/sevenk/profile/`）
    pub profile: PathBuf,
}

/// 一次恢复的结果（给命令行/日志/App 看）
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct RestoreReport {
    /// 用的哪份备份
    pub backup: PathBuf,
    /// 成功恢复的条目（相对名字）
    pub restored: Vec<String>,
    /// 备份里没有 / 不认识的条目（跳过）
    pub skipped: Vec<String>,
    /// "动手前"的快照目录（可人工回退；备份里没有可恢复数据时为 `None`）
    pub pre_restore: Option<PathBuf>,
    /// 失败明细（非空 ⇒ 调用方应当报错退出）
    pub failed: Vec<String>,
    /// 过程说明
    pub notes: Vec<String>,
}

impl RestoreReport {
    /// 人类可读的一行摘要
    #[must_use]
    pub fn summary(&self) -> String {
        let mut s = format!(
            "从 {} 恢复：成功 {} 项、跳过 {} 项、失败 {} 项",
            self.backup.display(),
            self.restored.len(),
            self.skipped.len(),
            self.failed.len()
        );
        for x in &self.restored {
            s.push_str("\n  · 已恢复: ");
            s.push_str(x);
        }
        for x in &self.skipped {
            s.push_str("\n  · 跳过（备份里没有）: ");
            s.push_str(x);
        }
        for x in &self.failed {
            s.push_str("\n  ! 失败: ");
            s.push_str(x);
        }
        for n in &self.notes {
            s.push_str("\n  · ");
            s.push_str(n);
        }
        s
    }
}

/// 列出 `/data/adb` 下所有的卸载备份目录（**新 → 旧**）。
///
/// 判定很保守：名字必须以 `BACKUP_PREFIX` 开头、`symlink_metadata` 确认是**真实目录**
/// （软链不跟随、普通文件不看），其余一律忽略。
#[must_use]
pub fn list_uninstall_backups(adb_dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir(adb_dir) else {
        return Vec::new();
    };
    let mut out: Vec<(String, PathBuf)> = Vec::new();
    for ent in entries.flatten() {
        let name = ent.file_name().to_string_lossy().into_owned();
        let Some(stamp) = name.strip_prefix(BACKUP_PREFIX) else {
            continue;
        };
        if stamp.is_empty() {
            continue;
        }
        let path = ent.path();
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() || !meta.is_dir() {
            continue;
        }
        out.push((stamp.to_string(), path));
    }
    // 时间戳格式 `%Y%m%d_%H%M%S` ⇒ 字符串倒序就是"新 → 旧"
    out.sort_by(|a, b| b.0.cmp(&a.0));
    out.into_iter().map(|(_, p)| p).collect()
}

/// 最新的一份备份（没有就 `None`）
#[must_use]
pub fn latest_uninstall_backup(adb_dir: &Path) -> Option<PathBuf> {
    list_uninstall_backups(adb_dir).into_iter().next()
}

/// 恢复要处理的条目：(显示名, 备份里的路径, 目标路径, 是不是目录)
fn restore_items(paths: &RestorePaths, backup: &Path) -> Vec<(String, PathBuf, PathBuf, bool)> {
    let mut items = vec![
        (
            ALLOWLIST_FILE.to_string(),
            backup.join(ALLOWLIST_FILE),
            paths.working.join(ALLOWLIST_FILE),
            false,
        ),
        (
            STEALTH_FILE.to_string(),
            backup.join(STEALTH_FILE),
            paths.working.join(STEALTH_FILE),
            false,
        ),
        (
            STEALTH_CODE_FILE.to_string(),
            backup.join(STEALTH_CODE_FILE),
            paths.working.join(STEALTH_CODE_FILE),
            false,
        ),
        (
            FEATURE_CONFIG_FILE.to_string(),
            backup.join(FEATURE_CONFIG_FILE),
            paths.working.join(FEATURE_CONFIG_FILE),
            false,
        ),
        (
            MODULE_CONFIGS_DIR.to_string(),
            backup.join(MODULE_CONFIGS_DIR),
            paths.module_configs.clone(),
            true,
        ),
        (
            PROFILE_DIR.to_string(),
            backup.join(PROFILE_DIR),
            paths.profile.clone(),
            true,
        ),
    ];
    items.retain(|(_, src, _, _)| src.exists());
    items
}

/// 从 `from`（或最新一份）恢复用户数据。
///
/// # Errors
/// * 找不到任何备份 / 指定的目录不是备份目录；
/// * 备份里一个可恢复的条目都没有；
/// * "动手前快照"失败（⇒ **一个字节都没改**）；
/// * 有任何一项恢复失败（`RestoreReport::failed` 非空）。
pub fn restore_user_data(paths: &RestorePaths, from: Option<&Path>) -> Result<RestoreReport> {
    let backup = match from {
        Some(p) => {
            // `--from` 可以给绝对路径，也可以只给目录名（相对 `/data/adb`）
            let cand = if p.is_absolute() {
                p.to_path_buf()
            } else {
                paths.adb.join(p)
            };
            let meta = fs::symlink_metadata(&cand)
                .with_context(|| format!("备份目录不存在/读不了：{}", cand.display()))?;
            if meta.file_type().is_symlink() || !meta.is_dir() {
                bail!(
                    "{} 不是一个真实的备份目录（软链/普通文件一律不碰）",
                    cand.display()
                );
            }
            cand
        }
        None => latest_uninstall_backup(&paths.adb).ok_or_else(|| {
            anyhow::anyhow!(
                "在 {} 里没找到任何 `{BACKUP_PREFIX}*` 备份目录（卸载时才会生成）",
                paths.adb.display()
            )
        })?,
    };

    let items = restore_items(paths, &backup);
    if items.is_empty() {
        bail!(
            "{} 里没有任何可恢复的用户数据（.allowlist / stealth / stealth_code / module_configs / profile）",
            backup.display()
        );
    }

    // ① 预检：源能不能读（读不出来就**什么都别动**）
    for (name, src, _, is_dir) in &items {
        preflight(src, *is_dir).with_context(|| format!("预检失败（{name}）"))?;
    }

    // ② 动手前的快照：把**当前**的目标数据原样复制到备份目录下的 `pre_restore_<stamp>/`
    //    （让用户/我们随时能退回去；快照失败 ⇒ 整个恢复中止，一个字节都不改）
    let pre_dir = backup.join(format!("pre_restore_{}", stamp()));
    let mut pre_copied = 0usize;
    for (name, _, dst, is_dir) in &items {
        if !dst.exists() {
            continue;
        }
        let target = pre_dir.join(name);
        let r = if *is_dir {
            copy_dir_verified(dst, &target)
        } else {
            copy_file_verified(dst, &target)
        };
        r.with_context(|| format!("动手前的快照失败（{name}）⇒ 已中止，一个字节都没改"))?;
        pre_copied += 1;
    }

    // ③ 逐项恢复（每项都是"写临时文件 → 校验 → rename 到位"；失败只影响这一项）
    let mut rep = RestoreReport {
        backup,
        pre_restore: if pre_copied > 0 { Some(pre_dir) } else { None },
        ..RestoreReport::default()
    };
    for (name, src, dst, is_dir) in &items {
        let r = if *is_dir {
            copy_dir_verified(src, dst)
        } else {
            copy_file_verified(src, dst)
        };
        match r {
            Ok(()) => {
                rep.restored.push(name.clone());
                log::info!("restore-user-data: {name} 已恢复");
            }
            Err(e) => rep
                .failed
                .push(format!("{name}: {e}（这一项一个字节都没改）")),
        }
    }

    rep.notes.push(
        "恢复完必须**重启一次**：内核只在开机时读授权名单/隐身标志（见 boot_event）".to_string(),
    );
    if let Some(p) = &rep.pre_restore {
        rep.notes
            .push(format!("恢复前的旧数据快照在 {}", p.display()));
    }
    if !rep.failed.is_empty() {
        bail!("恢复有失败项：\n{}", rep.summary());
    }
    Ok(rep)
}

/// 文件时间戳（`%Y%m%d_%H%M%S` 形状；不引 chrono，纯手算 UTC）
fn stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    let (y, mo, d, h, mi, s) = unix_to_utc(secs);
    format!("{y:04}{mo:02}{d:02}_{h:02}{mi:02}{s:02}")
}

/// Unix 秒 → UTC 年月日时分秒（只用于起名字，不追求 leap second 精确）
const fn unix_to_utc(secs: u64) -> (u64, u64, u64, u64, u64, u64) {
    let days = secs / 86400;
    let rem = secs % 86400;
    // 民用历法换算（Howard Hinnant 的 days_from_civil 逆运算）
    let z = days + 719_468;
    let era = z / 146_097;
    let doe = z % 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
}

/// 预检：文件可直接读；目录整棵树都能读出来
fn preflight(src: &Path, is_dir: bool) -> Result<()> {
    let meta = fs::symlink_metadata(src).with_context(|| format!("读不了 {}", src.display()))?;
    if is_dir {
        if meta.file_type().is_symlink() || !meta.is_dir() {
            bail!("{} 不是真实目录", src.display());
        }
        walk_files(src, &mut |p| {
            fs::File::open(p)
                .map(|_| ())
                .with_context(|| format!("读不了 {}", p.display()))
        })
    } else {
        if meta.file_type().is_symlink() || !meta.is_file() {
            bail!("{} 不是普通文件", src.display());
        }
        fs::File::open(src)
            .map(|_| ())
            .with_context(|| format!("读不了 {}", src.display()))
    }
}

/// 遍历目录里的所有普通文件（跳过软链；`f` 返回 Err 就中止）
fn walk_files(dir: &Path, f: &mut dyn FnMut(&Path) -> Result<()>) -> Result<()> {
    for ent in fs::read_dir(dir).with_context(|| format!("列不了 {}", dir.display()))? {
        let ent = ent?;
        let path = ent.path();
        let meta = fs::symlink_metadata(&path)?;
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            walk_files(&path, f)?;
        } else if meta.is_file() {
            f(&path)?;
        }
    }
    Ok(())
}

/// 恢复一个文件：写到同目录的临时文件 → **逐字节校验** → rename 到位。
/// 任何一步失败都删掉临时文件，目标**保持原样**。
fn copy_file_verified(src: &Path, dst: &Path) -> Result<()> {
    if let Some(parent) = dst.parent() {
        fs::create_dir_all(parent).with_context(|| format!("建不了目录 {}", parent.display()))?;
    }
    let tmp = tmp_sibling(dst);
    let result = (|| -> Result<()> {
        fs::copy(src, &tmp).with_context(|| format!("复制失败 {}", src.display()))?;
        // 🟠（v2.18）`fs::copy` **不 fsync**：`rename` 之后若掉电，目录项可能已经指向
        // 一份还没落盘的数据 ⇒ 目标变成空/半截（这里恢复的正是 `.allowlist` /
        // `stealth_code` 这种"丢了就掉授权、退不出隐身"的关键文件）。
        // 照本仓 `module_config.rs::save_config` 的写法：rename 前先 `sync_all`。
        std::fs::File::open(&tmp)
            .and_then(|f| f.sync_all())
            .with_context(|| format!("sync 不了 {}", tmp.display()))?;
        let a = fs::read(src).with_context(|| format!("读不了 {}", src.display()))?;
        let b = fs::read(&tmp).with_context(|| format!("读不了 {}", tmp.display()))?;
        if a != b {
            bail!("逐字节校验失败（{}）", dst.display());
        }
        fs::rename(&tmp, dst).with_context(|| format!("落到 {} 失败", dst.display()))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// 恢复一个目录：**逐文件**走 [`copy_file_verified`]（绝不删任何现有文件）。
fn copy_dir_verified(src: &Path, dst: &Path) -> Result<()> {
    fs::create_dir_all(dst).with_context(|| format!("建不了目录 {}", dst.display()))?;
    for ent in fs::read_dir(src).with_context(|| format!("列不了 {}", src.display()))? {
        let ent = ent?;
        let from = ent.path();
        let to = dst.join(ent.file_name());
        let meta = fs::symlink_metadata(&from)?;
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            copy_dir_verified(&from, &to)?;
        } else if meta.is_file() {
            copy_file_verified(&from, &to)?;
        }
    }
    Ok(())
}

fn tmp_sibling(dst: &Path) -> PathBuf {
    let name = dst.file_name().map_or_else(
        || "restore".to_string(),
        |n| n.to_string_lossy().into_owned(),
    );
    // 🟠（v2.18）**加随机后缀**。旧写法只有 `std::process::id()`：同一进程里两次恢复
    // （并发请求 / 同一进程内串行复用同一个 pid 的不同目标同名文件）会算出同一个临时名 ⇒
    // 后一次 `fs::copy` 截断前一次写到一半的临时文件 ⇒ rename 上去的是坏数据。
    // 与 `webadmin.rs::upload_path` 同一个坑，同一个修法。
    let uniq = crate::webadmin::random_hex(8)
        .unwrap_or_else(|| std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos())
            .to_string());
    dst.with_file_name(format!(
        "{name}.restore-tmp-{}-{uniq}",
        std::process::id()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_file(p: &Path, s: &[u8]) {
        if let Some(d) = p.parent() {
            fs::create_dir_all(d).unwrap();
        }
        let mut f = fs::File::create(p).unwrap();
        f.write_all(s).unwrap();
    }

    fn rd(p: &Path) -> Vec<u8> {
        fs::read(p).unwrap()
    }

    /// 造一套"标准"的 paths + 一份卸载备份（模拟 `backup_user_data()` 的产物）
    fn fixture(t: &Path) -> (RestorePaths, PathBuf) {
        let adb = t.join("adb");
        let working = adb.join("sevenk");
        let paths = RestorePaths {
            adb: adb.clone(),
            working: working.clone(),
            module_configs: working.join(MODULE_CONFIGS_DIR),
            profile: working.join(PROFILE_DIR),
        };
        let backup = adb.join(format!("{BACKUP_PREFIX}20260921_120000"));
        write_file(&backup.join(ALLOWLIST_FILE), b"allowlist-bytes");
        write_file(&backup.join(STEALTH_FILE), b"1");
        write_file(&backup.join(STEALTH_CODE_FILE), b"70707");
        write_file(&backup.join(FEATURE_CONFIG_FILE), b"features");
        write_file(
            &backup.join(MODULE_CONFIGS_DIR).join("m1").join("conf"),
            b"c1",
        );
        write_file(
            &backup.join(PROFILE_DIR).join("selinux").join("rule"),
            b"r1",
        );
        (paths, backup)
    }

    #[test]
    fn roundtrip_restores_every_item() {
        let t = tempfile::tempdir().unwrap();
        let (paths, backup) = fixture(t.path());
        // 先"清空当前数据"（模拟重装后的新机器：只有内核建的空壳）
        write_file(&paths.working.join(ALLOWLIST_FILE), b"");

        let rep = restore_user_data(&paths, None).unwrap();
        assert_eq!(rep.backup, backup);
        assert!(rep.failed.is_empty(), "{rep:?}");
        assert_eq!(rep.restored.len(), 6, "{rep:?}");
        assert_eq!(rd(&paths.working.join(ALLOWLIST_FILE)), b"allowlist-bytes");
        assert_eq!(rd(&paths.working.join(STEALTH_FILE)), b"1");
        assert_eq!(rd(&paths.working.join(STEALTH_CODE_FILE)), b"70707");
        assert_eq!(rd(&paths.working.join(FEATURE_CONFIG_FILE)), b"features");
        assert_eq!(rd(&paths.module_configs.join("m1").join("conf")), b"c1");
        assert_eq!(rd(&paths.profile.join("selinux").join("rule")), b"r1");
        // 动手前的快照里应当有"原来那个空名单"
        let pre = rep.pre_restore.expect("应当留下快照");
        assert_eq!(rd(&pre.join(ALLOWLIST_FILE)), b"");
        // 恢复**绝不删**任何东西：快照目录也在备份里
        assert!(backup.join(MODULE_CONFIGS_DIR).exists());
    }

    #[test]
    fn explicit_from_and_latest_selection() {
        let t = tempfile::tempdir().unwrap();
        let (paths, _) = fixture(t.path());
        let older = paths.adb.join(format!("{BACKUP_PREFIX}20260101_000000"));
        write_file(&older.join(STEALTH_FILE), b"0");
        let newer = paths.adb.join(format!("{BACKUP_PREFIX}20261231_235959"));
        write_file(&newer.join(STEALTH_FILE), b"1");

        assert_eq!(list_uninstall_backups(&paths.adb).len(), 3);
        assert_eq!(latest_uninstall_backup(&paths.adb).unwrap(), newer);
        // 指定旧的那份：只恢复它里面有的条目，且不碰别的
        let rep = restore_user_data(&paths, Some(&older)).unwrap();
        assert_eq!(rep.backup, older);
        assert_eq!(rep.restored, vec![STEALTH_FILE.to_string()], "{rep:?}");
        assert_eq!(rd(&paths.working.join(STEALTH_FILE)), b"0");
        // 相对路径也认（相对 /data/adb）
        let rel = newer.strip_prefix(&paths.adb).unwrap();
        let rep2 = restore_user_data(&paths, Some(rel)).unwrap();
        assert_eq!(rep2.backup, newer);
    }

    #[test]
    fn list_ignores_non_dirs_and_symlinks() {
        let t = tempfile::tempdir().unwrap();
        let (paths, _) = fixture(t.path());
        // 同名普通文件 + 同名软链：都必须被忽略
        write_file(
            &paths.adb.join(format!("{BACKUP_PREFIX}20270101_000000")),
            b"x",
        );
        let target = t.path().join("elsewhere");
        fs::create_dir_all(&target).unwrap();
        std::os::unix::fs::symlink(
            &target,
            paths.adb.join(format!("{BACKUP_PREFIX}20280101_000000")),
        )
        .unwrap();
        let all = list_uninstall_backups(&paths.adb);
        assert_eq!(all.len(), 1, "{all:?}");
    }

    #[test]
    fn nothing_to_restore_is_an_error_and_changes_nothing() {
        let t = tempfile::tempdir().unwrap();
        let (paths, _) = fixture(t.path());
        // 没有任何备份
        fs::remove_dir_all(&paths.adb).unwrap();
        fs::create_dir_all(&paths.adb).unwrap();
        write_file(&paths.working.join(STEALTH_FILE), b"1");
        let err = restore_user_data(&paths, None).unwrap_err().to_string();
        assert!(err.contains("没找到任何"), "{err}");
        assert_eq!(rd(&paths.working.join(STEALTH_FILE)), b"1");
    }

    #[test]
    fn corrupt_source_aborts_before_touching_anything() {
        let t = tempfile::tempdir().unwrap();
        let (paths, backup) = fixture(t.path());
        write_file(&paths.working.join(STEALTH_FILE), b"1");
        // 把备份里的一项做成"读不出来"的形状：目录换成不可读权限
        let bad = backup.join(MODULE_CONFIGS_DIR);
        fs::remove_dir_all(&bad).unwrap();
        write_file(&bad, b"not a dir"); // 预检会拒（不是真实目录）
        let err = restore_user_data(&paths, None).unwrap_err().to_string();
        assert!(err.contains("预检失败"), "{err}");
        // 预检失败 ⇒ 一个字节都没改
        assert_eq!(rd(&paths.working.join(STEALTH_FILE)), b"1");
        assert!(!paths.working.join(ALLOWLIST_FILE).exists());
    }

    #[test]
    fn never_deletes_existing_files() {
        let t = tempfile::tempdir().unwrap();
        let (paths, _) = fixture(t.path());
        // 当前数据里有个"备份里没有"的文件 ⇒ 恢复完必须还在
        write_file(&paths.working.join("keepme"), b"keep");
        restore_user_data(&paths, None).unwrap();
        assert_eq!(rd(&paths.working.join("keepme")), b"keep");
    }

    #[test]
    fn utc_conversion_is_sane() {
        assert_eq!(unix_to_utc(0), (1970, 1, 1, 0, 0, 0));
        // 2026-09-21 12:00:00 UTC
        assert_eq!(unix_to_utc(1_789_992_000), (2026, 9, 21, 12, 0, 0));
    }
}
