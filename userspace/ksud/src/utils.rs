use anyhow::{Context, Error, Ok, Result, bail};
use rustix::fs::{Mode, OFlags, open};
use rustix::process::setpgid;
use rustix::stdio::{dup2_stderr, dup2_stdin, dup2_stdout};
use std::{
    ffi::{CStr, CString, c_char, c_void},
    fs::{File, OpenOptions, create_dir_all, remove_file, write},
    io::{
        ErrorKind::{AlreadyExists, NotFound},
        Write,
    },
    path::Path,
    process::Command,
};

use crate::defs::KSU_TEMP_BACKUP_DIR_NAME;
use crate::{assets, boot_patch, defs, ksucalls, module, restorecon};
#[allow(unused_imports)]
use std::fs::{Permissions, set_permissions};
#[cfg(unix)]
use std::os::unix::prelude::PermissionsExt;

use std::path::PathBuf;

use crate::boot_patch::BootRestoreArgs;

use rustix::{
    process,
    thread::{LinkNameSpaceType, move_into_link_name_space},
};

type PropertyReadCallback = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char, u32);

unsafe extern "C" {
    fn __system_property_find(name: *const c_char) -> *const c_void;
    fn __system_property_read_callback(
        property_info: *const c_void,
        callback: PropertyReadCallback,
        cookie: *mut c_void,
    );
}

#[macro_export]
macro_rules! debug_select {
    ($debug:expr, $release:expr) => {{
        #[cfg(debug_assertions)]
        {
            $debug
        }
        #[cfg(not(debug_assertions))]
        {
            $release
        }
    }};
}

pub fn ensure_clean_dir(dir: impl AsRef<Path>) -> Result<()> {
    let path = dir.as_ref();
    log::debug!("ensure_clean_dir: {}", path.display());
    // 🟡8（v2.4）：用 `symlink_metadata`（**不跟随软链接**）。
    // 旧写法只判断 `path.exists()` 就 `remove_dir_all(path)`：
    // 若 `path` 本身是个软链接，`exists()` 会顺着链接看到目标目录，
    // 一旦删除语义落到"目标目录"上，就等于让 root 误删别处的真目录。
    // 现在：软链接只删链接本身；真目录才递归删；普通文件就删文件。
    match std::fs::symlink_metadata(path) {
        std::result::Result::Ok(meta) if meta.file_type().is_symlink() => {
            log::warn!(
                "ensure_clean_dir: {} is a symlink, removing the link itself only",
                path.display()
            );
            std::fs::remove_file(path)?;
        }
        std::result::Result::Ok(meta) if meta.is_dir() => std::fs::remove_dir_all(path)?,
        std::result::Result::Ok(_) => std::fs::remove_file(path)?,
        Err(err) if err.kind() == NotFound => {}
        Err(err) => {
            return Err(Error::from(err))
                .with_context(|| format!("failed to inspect {}", path.display()));
        }
    }
    Ok(std::fs::create_dir_all(path)?)
}

pub fn ensure_file_exists<T: AsRef<Path>>(file: T) -> Result<()> {
    match File::options().write(true).create_new(true).open(&file) {
        std::result::Result::Ok(_) => Ok(()),
        Err(err) => {
            if err.kind() == AlreadyExists && file.as_ref().is_file() {
                Ok(())
            } else {
                Err(Error::from(err))
                    .with_context(|| format!("{} is not a regular file", file.as_ref().display()))
            }
        }
    }
}

pub fn ensure_dir_exists<T: AsRef<Path>>(dir: T) -> Result<()> {
    let result = create_dir_all(&dir);
    if dir.as_ref().is_dir() && result.is_ok() {
        Ok(())
    } else {
        bail!("{} is not a regular directory", dir.as_ref().display())
    }
}

pub fn ensure_binary<T: AsRef<Path>>(
    path: T,
    contents: &[u8],
    ignore_if_exist: bool,
) -> Result<()> {
    if ignore_if_exist && path.as_ref().exists() {
        return Ok(());
    }

    ensure_dir_exists(path.as_ref().parent().ok_or_else(|| {
        anyhow::anyhow!(
            "{} does not have parent directory",
            path.as_ref().to_string_lossy()
        )
    })?)?;

    if let Err(e) = remove_file(path.as_ref())
        && e.kind() != NotFound
    {
        return Err(Error::from(e))
            .with_context(|| format!("failed to unlink {}", path.as_ref().display()));
    }

    write(&path, contents)?;
    #[cfg(unix)]
    set_permissions(&path, Permissions::from_mode(0o755))?;
    Ok(())
}

unsafe extern "C" fn property_read_callback(
    cookie: *mut c_void,
    _name: *const c_char,
    value: *const c_char,
    _serial: u32,
) {
    if cookie.is_null() || value.is_null() {
        return;
    }

    let result = unsafe { &mut *cookie.cast::<Option<String>>() };
    let value = unsafe { CStr::from_ptr(value) };
    *result = Some(value.to_string_lossy().into_owned());
}

pub fn getprop(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let property_info = unsafe { __system_property_find(name.as_ptr()) };
    if property_info.is_null() {
        return None;
    }

    let mut value = None;
    unsafe {
        __system_property_read_callback(
            property_info,
            property_read_callback,
            std::ptr::addr_of_mut!(value).cast(),
        );
    }
    value
}

pub fn is_safe_mode() -> bool {
    let safemode = getprop("persist.sys.safemode")
        .as_ref()
        .is_some_and(|prop| prop == "1")
        || getprop("ro.sys.safemode")
            .as_ref()
            .is_some_and(|prop| prop == "1");
    log::info!("safemode: {safemode}");
    if safemode {
        return true;
    }
    let safemode = ksucalls::check_kernel_safemode();
    log::info!("kernel_safemode: {safemode}");
    safemode
}

pub fn get_zip_uncompressed_size(zip_path: &str) -> Result<u64> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(zip_path)?)?;
    // 🟡（v2.18）上游这里是 `.map(|i| zip.by_index(i).unwrap().size())`：一个**损坏的
    // zip 元数据**（中央目录项类型不对/被截断）就能让 `by_index` 返回 `Err`，而 `unwrap()`
    // 直接 panic —— 崩掉的是 root 守护进程里的 ksud（用户看到的是"装模块装崩了"）。
    // 改成冒泡成 `Err`：语义和上游一致（都是**拒绝这个包**），但没有 panic 面。
    // `saturating_add` 顺带把"恶意包报超大 size ⇒ 求和溢出"也挡掉。
    let mut total: u64 = 0;
    for i in 0..zip.len() {
        total = total.saturating_add(zip.by_index(i)?.size());
    }
    Ok(total)
}

pub fn switch_mnt_ns(pid: i32) -> Result<()> {
    use rustix::{
        fd::AsFd,
        fs::{Mode, OFlags, open},
    };
    let path = format!("/proc/{pid}/ns/mnt");
    let fd = open(path, OFlags::RDONLY, Mode::from_raw_mode(0))?;
    let current_dir = std::env::current_dir();
    move_into_link_name_space(fd.as_fd(), Some(LinkNameSpaceType::Mount))?;
    if let std::result::Result::Ok(current_dir) = current_dir {
        let _ = std::env::set_current_dir(current_dir);
    }
    Ok(())
}

fn switch_cgroup(grp: &str, pid: u32) {
    let path = Path::new(grp).join("cgroup.procs");
    if !path.exists() {
        return;
    }

    let fp = OpenOptions::new().append(true).open(path);
    if let std::result::Result::Ok(mut fp) = fp {
        let _ = write!(fp, "{pid}");
    }
}

pub fn switch_cgroups() {
    let pid = std::process::id();
    switch_cgroup("/acct", pid);
    switch_cgroup("/dev/cg2_bpf", pid);
    switch_cgroup("/sys/fs/cgroup", pid);

    if getprop("ro.config.per_app_memcg")
        .as_ref()
        .is_none_or(|prop| prop != "false")
    {
        switch_cgroup("/dev/memcg/apps", pid);
    }
}

pub fn umask(mask: u32) {
    process::umask(rustix::fs::Mode::from_raw_mode(mask));
}

pub fn has_magisk() -> bool {
    which::which("magisk").is_ok()
}

fn link_ksud_to_bin() -> Result<()> {
    let ksu_bin = PathBuf::from(defs::DAEMON_PATH);
    let ksu_bin_link = PathBuf::from(defs::DAEMON_LINK_PATH);
    if ksu_bin.exists() && !ksu_bin_link.exists() {
        std::os::unix::fs::symlink(&ksu_bin, &ksu_bin_link)?;
    }
    Ok(())
}

/// 🟡3（v2.8 审计）：删除 `replace_file` 失败路径上留下的半成品（`<dst>.new`）。
///
/// 只清**临时文件**，目标文件 `dst` 一个字节都不碰 —— 所以任何失败路径都是
/// "还是完整可用的旧文件，且目录里没有垃圾"。
fn cleanup_leftover(tmp: &Path) {
    if let Err(e) = std::fs::remove_file(tmp) {
        // 清不掉（比如只读目录）也要让人查得到，别静默。
        log::warn!("failed to remove leftover {}: {e}", tmp.display());
    }
}

/// 🔴2（v2.4）：**原子替换一个文件**（先写临时文件 + 读回校验 + `rename`）。
///
/// 为什么不能用「`remove_file(dst)` → `copy(src, dst)`」：
///   拷到一半失败（断电 / 空间不足 / 源文件被删）= 目标位置上**什么都没有**。
///   `install()` 的目标正好就是 `/data/adb/ksud` 自己 —— 写坏它 = 设备上再没有 ksud；
///   而 `late_load.rs` 每次热加载都会调 `install()`，这个窗口是真实存在的。
///
/// 现在的顺序：新内容先落到 `<dst>.new` → **读回逐字节比对** → `rename` 覆盖。
/// `rename` 在同一文件系统内是原子的 ⇒ 最终要么是完整的新文件，要么还是完整的旧文件。
fn replace_file(src: &Path, dst: &Path) -> Result<()> {
    let bytes = std::fs::read(src).with_context(|| format!("failed to read {}", src.display()))?;
    let Some(file_name) = dst.file_name() else {
        bail!("{} has no file name", dst.display());
    };
    let mut tmp_name = file_name.to_os_string();
    tmp_name.push(".new");
    let tmp = dst.with_file_name(tmp_name);

    {
        let mut file = File::create(&tmp)
            .with_context(|| format!("failed to create {}", tmp.display()))?;
        file.write_all(&bytes)
            .with_context(|| format!("failed to write {}", tmp.display()))?;
        file.sync_all()
            .with_context(|| format!("failed to sync {}", tmp.display()))?;
    }

    // 读回校验：写进去的必须和源一模一样。不一致 = 半成品，**保留旧文件**、删掉半成品后报错。
    let written = std::fs::read(&tmp)
        .with_context(|| format!("failed to read back {}", tmp.display()))?;
    if written != bytes {
        cleanup_leftover(&tmp);
        bail!(
            "verification failed after writing {} ({} bytes vs source {} bytes); \
             the existing file was left untouched",
            tmp.display(),
            written.len(),
            bytes.len()
        );
    }
    // 🟡3（v2.8 审计）：这两步失败时也要把半成品 `<dst>.new` 清掉 ——
    // 旧写法直接 `?` 返回，目标目录里会**永久留下一个 0.7 MB 的 *.new 垃圾**
    // （`install()` 每次热加载都可能走到这里，攒起来就是白占空间）。
    // 注意：只清"我们自己刚写的那个临时文件"，**绝不动 `dst` 本身**。
    #[cfg(unix)]
    if let Err(e) = set_permissions(&tmp, Permissions::from_mode(0o755)) {
        cleanup_leftover(&tmp);
        return Err(e).with_context(|| format!("failed to chmod {}", tmp.display()));
    }

    if let Err(e) = std::fs::rename(&tmp, dst) {
        cleanup_leftover(&tmp);
        return Err(e)
            .with_context(|| format!("failed to move {} -> {}", tmp.display(), dst.display()));
    }
    Ok(())
}

pub fn install(libadbroot: Option<PathBuf>, data_path: Option<PathBuf>) -> Result<()> {
    ensure_dir_exists(defs::ADB_DIR)?;
    // 🔴2（v2.4）：原子替换（不再"先删后拷"）。失败时旧 ksud 原样保留。
    replace_file(Path::new("/proc/self/exe"), Path::new(defs::DAEMON_PATH))
        .context("failed to install the ksud binary")?;
    restorecon::lsetfilecon(defs::DAEMON_PATH, restorecon::KSU_CON)?;
    // install binary assets
    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    link_ksud_to_bin()?;

    if let Some(libadbroot) = libadbroot {
        ensure_dir_exists(defs::LIBRARY_DIR)?;
        // 🔴2（v2.4）：同样原子替换；**不再 `let _ =` 吞错** ——
        // libadbroot 装失败会在别处表现为莫名其妙的 adb root 故障，还查不到原因。
        replace_file(&libadbroot, Path::new(defs::LIBADBROOT_PATH))
            .context("failed to install libadbroot.so")?;
    }

    if let Some(data_path) = data_path {
        let backup_path = data_path.join(KSU_TEMP_BACKUP_DIR_NAME);
        if backup_path.is_dir() {
            let mut leftovers = 0usize;
            for ent in backup_path.read_dir()? {
                let ent = ent?;
                if !ent.file_type().is_ok_and(|v| v.is_file()) {
                    continue;
                }
                let name = ent.file_name().to_string_lossy().to_string();
                if !name.starts_with(defs::KSU_BACKUP_FILE_PREFIX) {
                    leftovers += 1;
                    continue;
                }
                let target = Path::new(defs::KSU_BACKUP_DIR).join(&name);
                // 🟡8（v2.4）：搬完必须**比对**（见 move_backup_verified）。
                // 旧写法 `rename` 失败 → `copy`（不比对）→ 无条件 `remove_dir_all`：
                // 一次 ENOSPC/EIO 就能让"原厂备份"永久消失 = restore 的退路没了。
                move_backup_verified(&ent.path(), &target)
                    .with_context(|| format!("failed to move boot backup {name}"))?;
                log::info!("move boot backup {name}");
            }
            if leftovers == 0 {
                std::fs::remove_dir_all(&backup_path)?;
            } else {
                // 🟡8：暂存目录里还有不认识的文件 → **不删整个目录**（可能藏着别的东西）。
                log::warn!(
                    "{} still holds {leftovers} unrecognized file(s); keeping the directory",
                    backup_path.display()
                );
            }
        }
    }

    Ok(())
}

/// 🟡8（v2.4）：把暂存的启动镜像备份搬到正式目录，**搬完必须比对**。
///
/// `rename` 成功 = 同一文件系统内的原子移动，内容不可能变，直接信。
/// `rename` 失败（跨文件系统）才走 `copy`——那就必须**读回逐字节比对**，
/// 一致才允许删源文件；否则暂存目录会被保留，用户还有得救。
fn move_backup_verified(src: &Path, dst: &Path) -> Result<()> {
    ensure_dir_exists(defs::KSU_BACKUP_DIR)?;
    if std::fs::rename(src, dst).is_ok() {
        return Ok(());
    }
    std::fs::copy(src, dst).with_context(|| format!("copy {} -> {}", src.display(), dst.display()))?;
    let original = std::fs::read(src).with_context(|| format!("read {}", src.display()))?;
    let moved = std::fs::read(dst).with_context(|| format!("read {}", dst.display()))?;
    if original != moved {
        bail!(
            "backup move verification failed: {} does not match {}; \
             the temporary copy was kept",
            dst.display(),
            src.display()
        );
    }
    std::fs::remove_file(src).with_context(|| format!("remove {}", src.display()))?;
    Ok(())
}

/// 🔴1（v2.4）：卸载之前把**用户数据**备份到 `/data/adb/sevenk_uninstall_backup_<ts>/`。
///
/// 为什么必须备份：`.allowlist`（授权名单）/ `stealth`（隐身标志）/ `stealth_code`（拨号密令）
/// / `module_configs/` 这些是**用户自己攒出来的东西**；`uninstall()` 下一步就会
/// `remove_dir_all(WORKING_DIR)` 把它们一起抹掉。旧代码**一个字节都不备份**：
/// 用户重装后授权、密令、模块配置全没了（密令丢了甚至可能退不出隐身，
/// 而 `StealthCodeStore` 的注释还写着它"能跨卸载"）。
///
/// 备份目录刻意放在 `ADB_DIR` 下、**不在 `WORKING_DIR` 里**，
/// 所以后面删 `WORKING_DIR` 不会连备份一起删掉。
/// 任何一项备份失败都 `bail!` —— 绝不"假装备份过了"然后继续删。
fn backup_user_data() -> Result<Option<PathBuf>> {
    let working = Path::new(defs::WORKING_DIR);
    if !working.is_dir() {
        return Ok(None);
    }
    let stamp = chrono::Utc::now().format("%Y%m%d_%H%M%S");
    let dest = Path::new(defs::ADB_DIR).join(format!("sevenk_uninstall_backup_{stamp}"));
    ensure_dir_exists(&dest)?;

    let mut copied = 0usize;
    let mut failed: Vec<String> = Vec::new();

    // 关键文件（丢了 = 掉授权 / 退不出隐身）
    for name in crate::migrate::KEY_FILES {
        let src = working.join(name);
        if src.is_file() {
            match std::fs::copy(&src, dest.join(name)) {
                std::result::Result::Ok(_) => copied += 1,
                Err(e) => failed.push(format!("{name}: {e}")),
            }
        }
    }
    // 整目录：模块配置 / profile（SELinux 规则 + 模板）。
    // ⚠️ **刻意不备份 `log/`**：那是 sulog 的原始日志，可能有几十 MB，
    // 既不值得留、也会把"卸载"拖慢；真要看日志的用户在卸载前自己导。
    for (dir, name) in [
        (defs::MODULE_CONFIG_DIR, "module_configs"),
        (defs::PROFILE_DIR, "profile"),
    ] {
        let src = Path::new(dir);
        if src.is_dir() {
            match copy_dir_all(src, &dest.join(name)) {
                std::result::Result::Ok(()) => copied += 1,
                Err(e) => failed.push(format!("{name}/: {e}")),
            }
        }
    }
    // 二进制特性开关（纯配置，丢了只是回默认，顺手留一份）
    let feature = working.join(".feature_config");
    if feature.is_file() {
        match std::fs::copy(&feature, dest.join(".feature_config")) {
            std::result::Result::Ok(_) => copied += 1,
            Err(e) => failed.push(format!(".feature_config: {e}")),
        }
    }

    if !failed.is_empty() {
        bail!(
            "failed to back up user data to {}: {}\n\
             Refusing to continue the uninstall (nothing has been deleted yet).",
            dest.display(),
            failed.join("; ")
        );
    }
    if copied == 0 {
        let _ = std::fs::remove_dir_all(&dest);
        return Ok(None);
    }
    println!("- User data backed up to: {}", dest.display());
    println!("- (keep that folder if you ever want your grants / secret code back)");
    Ok(Some(dest))
}

/// 递归复制目录（备份用；软链接等其它类型**跳过**，不跟随）。
fn copy_dir_all(src: &Path, dst: &Path) -> Result<()> {
    ensure_dir_exists(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_all(&entry.path(), &target)?;
        } else if file_type.is_file() {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}

/// 🔴2（v2.11）`ksud restore-user-data` 的 Android 侧入口：把 `defs` 里的真实路径填进
/// 纯 std 的实现（[`crate::restore_user_data`]，宿主上有单测整条覆盖）。
///
/// 审计点名的缺口：`backup_user_data()` 一直在备份，但**全仓没有一处读回它** ✗
/// ⇒ "备份了但用户拿不回来" = 等于没备份。现在补上命令行 + App 一键恢复。
pub fn restore_user_data(from: Option<&Path>) -> Result<()> {
    let paths = crate::restore_user_data::RestorePaths {
        adb: PathBuf::from(defs::ADB_DIR),
        working: PathBuf::from(defs::WORKING_DIR),
        module_configs: PathBuf::from(defs::MODULE_CONFIG_DIR),
        profile: PathBuf::from(defs::PROFILE_DIR),
    };
    // 先看看有哪些备份（`--from` 写错时把候选列表打出来，省得用户猜）
    let available = crate::restore_user_data::list_uninstall_backups(&paths.adb);
    if available.is_empty() {
        println!("- No uninstall backup found under {}", paths.adb.display());
        println!("- (backups are created automatically by `ksud uninstall`)");
    } else {
        println!("- Found {} uninstall backup(s):", available.len());
        for p in &available {
            println!("    {}", p.display());
        }
    }

    let rep = crate::restore_user_data::restore_user_data(&paths, from)?;
    println!("{}", rep.summary());
    println!("- RESTART REQUIRED: the kernel only reads the allowlist / stealth flag at boot.");
    println!("- Nothing was deleted (only overwritten or added).");
    Ok(())
}

/// 🔴2（v2.11）删目录，**失败要报错**（不存在算成功）。
///
/// 🟡（v2.11）统一走 `symlink_metadata` **分流**（防御性）：
///   · 软链 ⇒ 只 `remove_file` 删**链接本身**（绝不顺着它 `remove_dir_all` 把目标目录也删了 ✗）；
///   · 真实目录 ⇒ `remove_dir_all`；
///   · 别的类型（普通文件…）⇒ `remove_file`。
/// 当前调用点传进来的都是我们自己建的目录（无触发路径），但"删除"这种事**一次都不许赌**。
fn remove_dir_all_checked(dir: &str) -> Result<()> {
    // ⚠️ 这里的 `Ok` 是 anyhow 导入的那个**函数**（见文件头的 `use`），
    // 所以匹配 Result 时必须写全 `std::result::Result::Ok`。
    let meta = match std::fs::symlink_metadata(dir) {
        std::result::Result::Ok(m) => m,
        Err(e) if e.kind() == NotFound => return Ok(()),
        Err(e) => return Err(Error::from(e)).with_context(|| format!("failed to stat {dir}")),
    };
    if meta.file_type().is_symlink() {
        // 软链 ⇒ 只删链接本身（绝不顺着它把目标目录也删了）
        return match std::fs::remove_file(dir) {
            std::result::Result::Ok(()) => Ok(()),
            Err(e) if e.kind() == NotFound => Ok(()),
            Err(e) => {
                Err(Error::from(e)).with_context(|| format!("failed to remove symlink {dir}"))
            }
        };
    }
    if meta.is_dir() {
        return match std::fs::remove_dir_all(dir) {
            std::result::Result::Ok(()) => Ok(()),
            Err(e) if e.kind() == NotFound => Ok(()),
            Err(e) => Err(Error::from(e)).with_context(|| format!("failed to remove {dir}")),
        };
    }
    // 别的类型（普通文件…）：删文件本身
    match std::fs::remove_file(dir) {
        std::result::Result::Ok(()) => Ok(()),
        Err(e) if e.kind() == NotFound => Ok(()),
        Err(e) => Err(Error::from(e)).with_context(|| format!("failed to remove file {dir}")),
    }
}

/// 🔴1（v2.4）：删文件，**失败要报错**（不存在算成功）。
fn remove_file_checked(file: &str) -> Result<()> {
    match std::fs::remove_file(file) {
        std::result::Result::Ok(()) => Ok(()),
        Err(e) if e.kind() == NotFound => Ok(()),
        Err(e) => Err(Error::from(e)).with_context(|| format!("failed to remove {file}")),
    }
}

/// 🔴3（v2.4）：只在目录**空了**才删它。
///
/// `/data/adb/modules` 与面具**共用**：双 root 机器上里面还躺着面具的模块，
/// 整目录删 = 不可逆地清掉别人的东西。所以这里只在自己那一份已经清完之后
/// 顺手把空壳目录删掉；目录非空就原样保留。
fn remove_dir_if_empty(dir: &str) -> Result<()> {
    match std::fs::remove_dir(dir) {
        std::result::Result::Ok(()) => Ok(()),
        Err(e) if e.kind() == NotFound => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::DirectoryNotEmpty => {
            println!("- Keeping {dir}: it still contains modules that are not ours");
            Ok(())
        }
        Err(e) => Err(Error::from(e)).with_context(|| format!("failed to remove {dir}")),
    }
}

/// 🔴1（v2.10）：卸载时删掉 `/data/adb/ksu` **兼容软链本身**（v2.9 漏了这一步 ✗）。
///
/// 为什么必须删：上面 `remove_dir_all_checked(WORKING_DIR)` 已经把 `/data/adb/sevenk`
/// 删掉了；若把那条软链留在原地，它就成了**指向不存在目标的死链** ——
/// 之后第三方模块（Zygisk Next 等）拿到的旧路径全是 ENOENT，而重装后 ksud 还可能
/// 把它当成"已经是软链（幂等跳过）"（v2.9 的缺口；v2.10 已让
/// `ensure_compat_link_at` 遇到它时会自愈，这里是从源头不留死链）。
///
/// 判定 + 删除的**权威实现**在 [`crate::migrate::remove_compat_link_at`]
/// （纯 std ⇒ 宿主上有单测逐条覆盖"只删我们的软链、绝不碰真实数据"）；
/// 这里只负责把结论打成人话。**删除失败也不让卸载失败**。
fn remove_legacy_compat_symlink() {
    use crate::migrate::CompatLinkRemoval;
    match crate::migrate::remove_compat_link_at(
        Path::new(crate::migrate::LEGACY_DIR),
        Path::new(crate::migrate::NEW_DIR),
    ) {
        CompatLinkRemoval::Removed => println!(
            "- Removed compat symlink {} (link only, no data touched)",
            crate::migrate::LEGACY_DIR
        ),
        CompatLinkRemoval::Absent => {}
        CompatLinkRemoval::Kept(why) => println!(
            "- Keeping {}: {why} (never touched)",
            crate::migrate::LEGACY_DIR
        ),
        CompatLinkRemoval::Failed(why) => println!(
            "- Keeping {}: {why} (the next ksud run will heal it)",
            crate::migrate::LEGACY_DIR
        ),
    }
}

pub fn uninstall(package_name: &str) -> Result<()> {
    // 🔴1（v2.4）：**顺序修正 —— 先还原启动镜像，再删任何东西**。
    // 字节精确的原厂备份就存在 WORKING_DIR（defs::KSU_BACKUP_DIR）里；
    // 旧顺序是"先 remove_dir_all(WORKING_DIR) → 再 restore"，等于
    // **先把退路删掉、再让 restore 找不到原厂镜像**（只剩弱的 rebuild_without_ksu）。
    //
    // 🟡（v2.11）文案补清楚两件事（用户最关心的就是"我数据还在吗"）：
    //   ① 这一步只还原**启动镜像**，用户数据**一个字节都还没删**；
    //   ② 下一步会先把用户数据备份出来，并明确告诉用户怎么拿回来
    //      （`ksud restore-user-data`；App 首页也有一键恢复入口）。
    println!("- Restore boot image.. (your data is NOT deleted yet)");
    boot_patch::restore(BootRestoreArgs {
        boot: None,
        flash: true,
        force_foreign: false,
        out: None,
        out_name: None,
    })?;

    // 🔴1（v2.4）：删之前先备份用户数据（`.allowlist`/`stealth`/`stealth_code`/
    // `module_configs/`…），并把备份路径打印给用户。备份失败 → 中止，不删、不重启。
    match backup_user_data()? {
        Some(dir) => println!(
            "- YOU CAN GET IT BACK LATER with: ksud restore-user-data   (or the App home screen button)\n\
             - backup kept at: {}",
            dir.display()
        ),
        None => println!("- No user data found to back up (nothing worth keeping)"),
    }

    // 🔴3（v2.4）：只处理**我们自己的**模块；`/data/adb/modules` 与面具共用，
    // 绝不能整目录删、也不能无差别打 remove 标记（判据见 module.rs 的 mark_all_modules）。
    if Path::new(defs::MODULE_DIR).exists() {
        println!("- Uninstall modules..");
        module::uninstall_all_modules()?;
        module::prune_modules()?;
    }

    println!("- Removing directories..");
    // 🔴1（v2.4）：**不再 `.ok()` 吞错**。旧写法四个 remove_dir_all 全是 `.ok()`，
    // 删失败也照样往下走、照样 reboot —— 用户以为卸载干净了，其实残留还在。
    // 现在任一失败就 `?` 冒泡：**中止且不 reboot**（错误信息里能看出是哪个目录）。
    remove_dir_all_checked(defs::WORKING_DIR)?;
    // 🔴1（v2.10）：新目录已经删了 ⇒ 顺手把 `/data/adb/ksu` 兼容软链**本身**也删掉，
    // 免得留下一条指向不存在目标的死链（第三方模块会一直 ENOENT）。
    // 只删确认是"我们那条软链"的；真实目录/别的东西一律不动（见函数注释）。
    remove_legacy_compat_symlink();
    remove_file_checked(defs::DAEMON_PATH)?;
    // 🔴3（v2.4）：**删掉了原来的 `remove_dir_all(MODULE_DIR)`** —— 那是与面具共用的
    // 目录，整目录删会不可逆地清掉面具的模块（双 root 设备）。我们自己的模块在上面
    // 已经打过标记、由 prune_modules 删过；剩下的如果不是空的，说明还有别人的东西，保留。
    remove_dir_if_empty(defs::MODULE_DIR)?;
    remove_dir_all_checked(defs::PREINIT_DIR_WATCHDOG)?;
    remove_dir_all_checked(defs::PREINIT_DIR_DEFAULT)?;

    println!("- Uninstall KernelSU manager..");
    Command::new("pm")
        .args(["uninstall", package_name])
        .spawn()?;
    println!("- Rebooting in 5 seconds..");
    std::thread::sleep(std::time::Duration::from_secs(5));
    Command::new("reboot").spawn()?;
    Ok(())
}

pub fn reset_std() -> Result<()> {
    let null_fd = open("/dev/null", OFlags::RDWR, Mode::empty())?;
    dup2_stdin(&null_fd)?;
    dup2_stdout(&null_fd)?;
    dup2_stderr(&null_fd)?;
    Ok(())
}

pub fn daemonize_with<F: FnOnce() -> Result<()>>(use_init_pgrp: bool, configure: F) -> Result<()> {
    if !create_daemon_impl(use_init_pgrp, configure)? {
        unsafe { libc::_exit(0) }
    }
    Ok(())
}

pub fn daemonize(use_init_pgrp: bool) -> Result<()> {
    daemonize_with(use_init_pgrp, || Ok(()))
}

pub fn create_daemon(use_init_pgrp: bool) -> Result<bool> {
    create_daemon_with(use_init_pgrp, || Ok(()))
}

pub fn create_daemon_with<F: FnOnce() -> Result<()>>(
    use_init_pgrp: bool,
    configure: F,
) -> Result<bool> {
    create_daemon_impl(use_init_pgrp, configure)
}

fn create_daemon_impl<F: FnOnce() -> Result<()>>(
    use_init_pgrp: bool,
    configure: F,
) -> Result<bool> {
    unsafe {
        let pid = libc::fork();
        if pid < 0 {
            bail!("fork error {}", std::io::Error::last_os_error());
        } else if pid > 0 {
            let mut status: i32 = -1;
            loop {
                if libc::waitpid(pid, &raw mut status, 0) < 0 {
                    if *libc::__errno() != libc::EINTR {
                        libc::_exit(1);
                    }
                } else {
                    break;
                }
            }
            if !libc::WIFEXITED(status) || libc::WEXITSTATUS(status) != 0 {
                bail!("child exited with unexpected status {status}")
            }
            return Ok(false);
        }
    }

    let do_configure = || -> Result<()> {
        detach_process_group(use_init_pgrp);
        switch_cgroups();
        configure()?;
        reset_std()?;

        unsafe {
            let pid = libc::fork();
            if pid < 0 {
                bail!("fork error {}", std::io::Error::last_os_error());
            } else if pid > 0 {
                libc::_exit(0);
            }
        }
        Ok(())
    };

    if let Err(e) = do_configure() {
        log::error!("failed to configure daemon: {e:?}");
        unsafe {
            libc::_exit(1);
        }
    }

    Ok(true)
}

pub fn detach_process_group(use_init_pgrp: bool) {
    if use_init_pgrp {
        if let Err(e) = ksucalls::set_init_pgrp() {
            log::error!("failed to switch to init group: {e:?}");
        } else {
            return;
        }
    }
    if let Err(e2) = setpgid(None, None) {
        log::error!("failed to set process group: {e2:?}");
    }
}
