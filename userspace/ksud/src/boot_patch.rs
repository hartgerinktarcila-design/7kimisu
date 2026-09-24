#![allow(clippy::ref_option, clippy::needless_pass_by_value)]

use std::fs::File;
use std::io::{Cursor, Seek, SeekFrom};
use std::path::Path;
use std::path::PathBuf;

use android_bootimg::cpio::{Cpio, CpioEntry};
use android_bootimg::parser::{BootImage, BootImageVersion, RamdiskImage};
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::Context;
use anyhow::Result;
use anyhow::anyhow;
use anyhow::bail;
use anyhow::ensure;
use memmap2::{Mmap, MmapOptions};
use regex_lite::Regex;

use crate::assets;

#[cfg(target_os = "android")]
mod android {
    use super::Result;
    // B2/B3 的纯判据定义在顶层（宿主机可单测），android 子模块里要显式引进来
    use super::{FlashFailureAction, flash_failure_action, image_fits_partition};
    pub(super) use crate::defs::{BACKUP_FILENAME, KSU_BACKUP_DIR, KSU_BACKUP_FILE_PREFIX};
    use crate::defs::{DEFAULT_PACKAGE_NAME, KSU_TEMP_BACKUP_DIR_NAME};
    use android_bootimg::cpio::{Cpio, CpioEntry};
    use anyhow::{Context, anyhow, bail, ensure};
    use regex_lite::Regex;
    use rustix::process::getuid;
    use std::fs::{File, OpenOptions};
    use std::io::{Read, Seek, SeekFrom, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::PermissionsExt;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use crate::utils;

    pub(super) fn ensure_gki_kernel() -> Result<()> {
        let version = get_kernel_version()?;
        let is_gki = version.0 == 5 && version.1 >= 10 || version.2 > 5;
        ensure!(is_gki, "only support GKI kernel");
        Ok(())
    }

    pub fn get_kernel_version() -> Result<(i32, i32, i32)> {
        let uname = rustix::system::uname();
        let version = uname.release().to_string_lossy();
        let re = Regex::new(r"(\d+)\.(\d+)\.(\d+)")?;
        if let Some(captures) = re.captures(&version) {
            let major = captures
                .get(1)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Major version parse error"))?;
            let minor = captures
                .get(2)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Minor version parse error"))?;
            let patch = captures
                .get(3)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Patch version parse error"))?;
            Ok((major, minor, patch))
        } else {
            Err(anyhow!("Invalid kernel version string"))
        }
    }

    fn parse_kmi(version: &str) -> Result<String> {
        let re = Regex::new(r"(.* )?(\d+\.\d+)(\S+)?(android\d+)(.*)")?;
        let cap = re
            .captures(version)
            .ok_or_else(|| anyhow::anyhow!("Failed to get KMI from boot/modules"))?;
        let android_version = cap.get(4).map_or("", |m| m.as_str());
        let kernel_version = cap.get(2).map_or("", |m| m.as_str());
        Ok(format!("{android_version}-{kernel_version}"))
    }

    fn parse_kmi_from_uname() -> Result<String> {
        let uname = rustix::system::uname();
        let version = uname.release().to_string_lossy();
        parse_kmi(&version)
    }

    fn parse_kmi_from_modules() -> Result<String> {
        use std::io::BufRead;
        // find a *.ko in /vendor/lib/modules
        let modfile = std::fs::read_dir("/vendor/lib/modules")?
            .filter_map(Result::ok)
            .find(|entry| entry.path().extension().is_some_and(|ext| ext == "ko"))
            .map(|entry| entry.path())
            .ok_or_else(|| anyhow!("No kernel module found"))?;
        let output = Command::new("modinfo").arg(modfile).output()?;
        for line in output.stdout.lines().map_while(Result::ok) {
            if line.starts_with("vermagic") {
                return parse_kmi(&line);
            }
        }
        bail!("Parse KMI from modules failed")
    }

    pub fn get_current_kmi() -> Result<String> {
        parse_kmi_from_uname().or_else(|_| parse_kmi_from_modules())
    }

    fn calculate_sha1(file_path: impl AsRef<Path>) -> Result<String> {
        use sha1::Digest;
        use std::io::Read;
        let mut file = std::fs::File::open(file_path.as_ref())?;
        let mut hasher = sha1::Sha1::new();
        let mut buffer = [0; 1024];

        loop {
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }

        let result = hasher.finalize();
        Ok(base16ct::lower::encode_string(&result))
    }

    fn find_backup_location(sha1: &String) -> Result<(File, String)> {
        let filename = format!("{KSU_BACKUP_FILE_PREFIX}{sha1}");
        let target = format!("{KSU_BACKUP_DIR}{filename}");
        if let Ok(target_file) = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&target)
        {
            return Ok((target_file, target));
        }

        // We have no permission to access /data/adb
        // Save it to /data/user_de/$USER/$PKG/boot_backup
        let user_id = getuid().as_raw() / 100_000;

        let backup_dir =
            format!("/data/user_de/{user_id}/{DEFAULT_PACKAGE_NAME}/{KSU_TEMP_BACKUP_DIR_NAME}");
        std::fs::remove_dir_all(&backup_dir).ok();
        std::fs::create_dir(&backup_dir)?;
        let backup_file = format!("{backup_dir}/{filename}");
        if let Ok(file) = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&backup_file)
        {
            return Ok((file, backup_file));
        }

        bail!("Both /data/adb/sevenk and {backup_dir} are not accessible!")
    }

    /// 备份当前 boot 镜像，并**校验备份确实写好了**（B1）。
    ///
    /// 返回值是备份文件的路径：刷写校验失败时要用它回滚（B3），所以必须能拿到。
    ///
    /// `record_stock` 决定要不要把这份备份登记成 ramdisk 里的 `stock_image.sha1`
    /// （restore 依赖它找回原厂镜像）：
    ///   · **只有当前镜像是原厂（Stock）时才登记**（判据见 `should_record_stock()`）；
    ///   · 当前镜像已经是我们的补丁 / 别人打的补丁时**不登记**，否则会把“已打补丁的镜像”
    ///     写成“原厂备份”，反而毁掉 restore 的退路。
    ///   · 用户显式 `--backup` **不影响**这个判断：它只决定“要不要做备份”，
    ///     备份文件本身照写（回滚要用）。
    pub(super) fn do_backup(cpio: &mut Cpio, image: &Path, record_stock: bool) -> Result<PathBuf> {
        let sha1 = calculate_sha1(image)?;
        let (mut target_file, target) = find_backup_location(&sha1)?;
        println!("- Backup stock boot image");
        let mut source = OpenOptions::new()
            .create(false)
            .truncate(false)
            .read(true)
            .write(false)
            .open(image)?;

        // Use io::copy instead of fs::copy to allow copy block device
        std::io::copy(&mut source, &mut target_file)
            .with_context(|| format!("failed to backup to {target}"))?;
        // 落盘后再回读校验，避免只在页缓存里“看起来对”
        target_file
            .sync_all()
            .with_context(|| format!("failed to sync backup {target}"))?;

        // B1：把刚写出来的备份**重新读一遍**，和源块设备的哈希比对；
        // 不一致就立刻 bail —— 没有一份可证明正确的备份，绝不允许往下写分区。
        let backup_sha1 = calculate_sha1(&target)?;
        if backup_sha1 != sha1 {
            bail!(
                "backup verification failed: {target} has sha1 {backup_sha1} but the source image has sha1 {sha1}.\n\
                 Refusing to continue: the backup is not trustworthy."
            );
        }
        println!("- Backup verified (sha1 {sha1})");

        if record_stock {
            let backup_file = CpioEntry::regular(0o755, Box::new(sha1));
            cpio.add(BACKUP_FILENAME, backup_file)?;
        } else {
            println!("- Keeping the existing stock image marker (this image is already patched)");
        }

        println!("- Stock image has been backup to");
        println!("- {target}");
        Ok(PathBuf::from(target))
    }

    pub(super) fn clean_backup(sha1: &str) -> Result<()> {
        println!("- Clean up backup");
        let backup_name = format!("{KSU_BACKUP_FILE_PREFIX}{sha1}");
        let dir = std::fs::read_dir(KSU_BACKUP_DIR)?;
        for entry in dir.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            if let Some(name) = path.file_name() {
                let name = name.to_string_lossy().to_string();
                if name != backup_name
                    && name.starts_with(KSU_BACKUP_FILE_PREFIX)
                    && std::fs::remove_file(path).is_ok()
                {
                    println!("- removed {name}");
                }
            }
        }
        Ok(())
    }

    /// 把校验失败的分区用备份原样写回去（B3）。
    ///
    /// 备份是 `do_backup()` 从同一个块设备整段拷出来的（长度 = 分区大小），
    /// 所以整段写回就等于恢复原状；写完再比对一次 sha1，确认真的回滚成功了。
    fn rollback_partition(blk: &mut File, partition: &str, backup: &Path) -> Result<()> {
        let mut source = OpenOptions::new()
            .create(false)
            .truncate(false)
            .read(true)
            .write(false)
            .open(backup)
            .with_context(|| format!("open backup {}", backup.display()))?;
        blk.seek(SeekFrom::Start(0))?;
        std::io::copy(&mut source, blk)
            .with_context(|| format!("write backup {} back", backup.display()))?;
        blk.sync_all().context("sync rolled back boot failed")?;

        let backup_sha1 = calculate_sha1(backup)?;
        let partition_sha1 = calculate_sha1(partition)?;
        ensure!(
            backup_sha1 == partition_sha1,
            "rollback verification failed: {partition} has sha1 {partition_sha1} but the backup has sha1 {backup_sha1}"
        );
        Ok(())
    }

    /// B3：把镜像写进分区，并读回做整段 SHA-256 校验。
    ///
    /// **这里任何一步失败都只返回 `Err`，不在这里决定怎么收场** ——
    /// `write_all` / `sync_all` / `seek` / `read_exact` / “读回来哈希对不上”
    /// 全部交给 `flash_partition` 的唯一出口 `flash_failure_error()` 处理
    /// （回滚写回并再验 sha1，或者明确要求“请勿重启，用电脑 fastboot 刷回备份”）。
    ///
    /// 之所以要包成 helper：以前这四步是散在 `flash_partition` 里的裸 `?`，
    /// 写一半失败（坏块 / ENOSPC / 被中断）会**直接返回，既不回滚也不提示**（v2.2 的 🟠）。
    fn write_and_verify(blk: &mut File, partition: &str, data: &[u8]) -> Result<()> {
        blk.write_all(data).context("flash boot failed")?;
        blk.sync_all().context("sync boot failed")?;

        // B3：写完读回来，整段 SHA-256 比对
        let expected = sha256::digest(data);
        blk.seek(SeekFrom::Start(0))
            .with_context(|| format!("seek {partition} for verification"))?;
        let mut readback = vec![0u8; data.len()];
        blk.read_exact(&mut readback)
            .with_context(|| format!("read back {partition} for verification"))?;
        let actual = sha256::digest(&readback);
        drop(readback);

        ensure!(
            expected == actual,
            "write verification failed (expected sha256 {expected}, got {actual})"
        );

        println!("- Write verified (sha256 {actual})");
        Ok(())
    }

    /// B3：刷写失败的**唯一出口** —— 全部失败路径共用同一条收场逻辑。
    ///
    /// 判据由纯函数 `flash_failure_action()`（宿主机可单测）给出：
    ///   1. 有备份，且回滚写回 + 再验 sha1 都成功 → 分区已恢复原样，报“设备应仍能开机”；
    ///   2. 没有备份，或者回滚本身也失败 → 分区可能已损坏，明确要求
    ///      「**请勿重启**，用电脑 fastboot 刷回备份」。
    ///
    /// 无论如何都返回 `Err`（调用方 `?` 出来 → `bail`），绝不静默放行。
    fn flash_failure_error(
        blk: &mut File,
        partition: &str,
        backup: Option<&Path>,
        error: &anyhow::Error,
    ) -> anyhow::Error {
        println!("- Flashing {partition} failed: {error:#}");

        let mut rollback_error: Option<anyhow::Error> = None;
        // 有备份就试着写回；**回滚本身失败也是一种失败**（rollback_error 会被记下来）
        let rollback_result: Option<Result<()>> = backup.map(|backup_path| {
            println!("- Rolling back from {}", backup_path.display());
            rollback_partition(blk, partition, backup_path)
        });
        let rolled_back = match rollback_result {
            Some(Ok(())) => true,
            Some(Err(e)) => {
                rollback_error = Some(e);
                false
            }
            None => false,
        };

        match flash_failure_action(backup.is_some(), rolled_back) {
            FlashFailureAction::RolledBack => anyhow!(
                "flashing {partition} failed: {error:#}\n\
                 the original partition content has been written back from the backup and verified with sha1,\n\
                 so the device should still boot. Please retry, or use the offline patch-to-file flow."
            ),
            FlashFailureAction::PartitionMayBeCorrupted => {
                let detail = rollback_error.map_or_else(
                    || "there is no verified backup to roll back to".to_string(),
                    |e| format!("the rollback itself failed: {e:#}"),
                );
                anyhow!(
                    "flashing {partition} failed: {error:#}\n\
                     and it could not be rolled back ({detail}).\n\
                     The partition may be corrupted: DO NOT REBOOT the device.\n\
                     Flash the backup back from a computer with fastboot, then reboot."
                )
            }
        }
    }

    /// 刷写 boot/init_boot 分区，带三道护栏：
    ///   · B2：写之前用 `BLKGETSIZE64` 确认镜像装得下（防止选错分区）；
    ///   · B3：写完 `sync` 后把分区读回来做整段 SHA-256 比对；
    ///   · B3：**任何一步失败**（写 / sync / seek / 读回 / 哈希不一致）都用 `backup` 回滚并再验 sha1，
    ///     回滚不了就明确要求“别重启、用电脑 fastboot 刷回备份”，然后 bail。
    ///
    /// `backup` 是 `do_backup()` 返回的备份路径；没有备份时也照样 bail，只是无法自动回滚。
    pub(super) fn flash_partition(
        partition: &str,
        data: &[u8],
        backup: Option<&Path>,
    ) -> Result<()> {
        let mut blk = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .truncate(false)
            .create(false)
            .open(partition)
            .with_context(|| format!("open {partition}"))?;
        unsafe {
            const BLKROSET: i32 = libc::_IO(0x12, 93);
            let mut val: libc::c_int = 0;
            if libc::ioctl(blk.as_raw_fd(), BLKROSET, &raw mut val) != 0 {
                bail!("Failed to set rw for {partition}: {}", *libc::__errno());
            }
        }

        // B2：先问一下分区到底多大。选错分区（比如把 boot 镜像写进别的分区）是最致命的，
        // 长度先对不上就直接拒绝。
        let part_size = unsafe {
            // BLKGETSIZE64 = _IOR(0x12, 114, size_t)，和上面的 BLKROSET 一样就地定义
            const BLKGETSIZE64: i32 = libc::_IOR::<libc::size_t>(0x12, 114);
            let mut size: u64 = 0;
            if libc::ioctl(blk.as_raw_fd(), BLKGETSIZE64, &raw mut size) != 0 {
                bail!(
                    "Failed to query the size of {partition}: {}. \
                     Cannot confirm the target partition, refusing to write.",
                    *libc::__errno()
                );
            }
            size
        };
        ensure!(
            image_fits_partition(data.len() as u64, part_size),
            "the boot image is {} bytes but partition {partition} is only {} bytes. \
             The wrong partition was probably selected; refusing to write.",
            data.len(),
            part_size
        );

        // B3：写 + sync + 读回校验全部在 helper 里；失败**一律**走同一条出口（回滚 or 明确别重启）。
        match write_and_verify(&mut blk, partition, data) {
            Ok(()) => Ok(()),
            Err(error) => Err(flash_failure_error(&mut blk, partition, backup, &error)),
        }
    }

    pub fn choose_boot_partition(
        kmi: &str,
        is_replace_kernel: bool,
        partition: &Option<String>,
    ) -> String {
        let slot_suffix = get_slot_suffix(false);
        let skip_init_boot = kmi.starts_with("android12-");
        let init_boot_exist =
            Path::new(&format!("/dev/block/by-name/init_boot{slot_suffix}")).exists();

        // if specific partition is specified, use it
        if let Some(part) = partition {
            return match part.as_str() {
                "boot" | "init_boot" | "vendor_boot" => part.clone(),
                _ => "boot".to_string(),
            };
        }

        // if init_boot exists and not skipping it, use it
        if !is_replace_kernel && init_boot_exist && !skip_init_boot {
            return "init_boot".to_string();
        }

        "boot".to_string()
    }

    pub fn get_slot_suffix(ota: bool) -> String {
        let mut slot_suffix = utils::getprop("ro.boot.slot_suffix").unwrap_or_default();
        if !slot_suffix.is_empty() && ota {
            if slot_suffix == "_a" {
                slot_suffix = "_b".to_string();
            } else {
                slot_suffix = "_a".to_string();
            }
        }
        slot_suffix
    }

    pub fn list_available_partitions() -> Vec<String> {
        let slot_suffix = get_slot_suffix(false);
        let candidates = vec!["boot", "init_boot", "vendor_boot"];
        candidates
            .into_iter()
            .filter(|name| Path::new(&format!("/dev/block/by-name/{name}{slot_suffix}")).exists())
            .map(ToString::to_string)
            .collect()
    }

    pub(super) fn auto_boot_partition_path(
        kmi: &str,
        ota: bool,
        is_replace_kernel: bool,
        partition: &Option<String>,
    ) -> PathBuf {
        let slot_suffix = get_slot_suffix(ota);
        let name = choose_boot_partition(kmi, is_replace_kernel, partition);
        PathBuf::from(format!("/dev/block/by-name/{name}{slot_suffix}"))
    }

    pub(super) fn post_ota() -> Result<()> {
        use crate::assets::BOOTCTL_PATH;
        use crate::defs::ADB_DIR;
        let status = Command::new(BOOTCTL_PATH).arg("hal-info").status()?;
        if !status.success() {
            return Ok(());
        }

        let current_slot = Command::new(BOOTCTL_PATH)
            .arg("get-current-slot")
            .output()?
            .stdout;
        let current_slot = String::from_utf8(current_slot)?;
        let current_slot = current_slot.trim();
        let target_slot = i32::from(current_slot == "0");

        Command::new(BOOTCTL_PATH)
            .arg(format!("set-active-boot-slot {target_slot}"))
            .status()?;

        let post_fs_data = Path::new(ADB_DIR).join("post-fs-data.d");
        utils::ensure_dir_exists(&post_fs_data)?;
        let post_ota_sh = post_fs_data.join("post_ota.sh");

        let sh_content = format!(
            r"
{BOOTCTL_PATH} mark-boot-successful
rm -f {BOOTCTL_PATH}
rm -f /data/adb/post-fs-data.d/post_ota.sh
"
        );

        std::fs::write(&post_ota_sh, sh_content)?;
        std::fs::set_permissions(post_ota_sh, std::fs::Permissions::from_mode(0o755))?;

        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

fn map_file(file: &Path) -> Result<Mmap> {
    let mut f = File::open(file).with_context(|| format!("open {}", file.display()))?;
    let len = f
        .seek(SeekFrom::End(0))
        .with_context(|| format!("seek end of {}", file.display()))? as usize;
    let mmap = unsafe { MmapOptions::new().len(len).map(&f)? };
    Ok(mmap)
}

pub fn parse_kmi(buffer: &[u8]) -> Result<String> {
    let re = Regex::new(r"(\d+\.\d+)(?:\S+)?(android\d+)").context("Failed to compile regex")?;
    buffer
        .windows(4)
        .enumerate()
        .filter(|(_, x)| {
            x[1] == b'.'
                && x[2].is_ascii_digit()
                && match x[0] {
                    b'5' => x[3].is_ascii_digit(),
                    b'6'..=b'9' => true,
                    _ => false,
                }
        })
        .find_map(|(i, _)| {
            let a = &buffer[i..buffer.len().min(i + 100)];
            if let Some(e) = a.iter().position(|c| *c == 0)
                && let Ok(s) = std::str::from_utf8(&a[..e])
                && let Some(caps) = re.captures(s)
                && let (Some(kernel_version), Some(android_version)) = (caps.get(1), caps.get(2))
            {
                Some(format!(
                    "{}-{}",
                    android_version.as_str(),
                    kernel_version.as_str()
                ))
            } else {
                None
            }
        })
        .ok_or_else(|| {
            println!("- Failed to get KMI version");
            anyhow!("Try to choose LKM manually")
        })
}

fn parse_kmi_from_kernel(kernel: &Path) -> Result<String> {
    let data = std::fs::read(kernel).context("Failed to read kernel file")?;
    parse_kmi(&data)
}

fn parse_kmi_from_boot(image: &Path) -> Result<String> {
    let data = map_file(image)?;
    let boot = BootImage::parse(&data)?;
    if let Some(kernel) = boot.get_blocks().get_kernel() {
        let mut output = Vec::<u8>::new();
        kernel.dump(&mut output, false)?;
        parse_kmi(&output)
    } else {
        bail!("no kernel found in boot image")
    }
}

/// For vendor boot, prefer the `init_boot` ramdisk entry over the one with empty name,
/// matching the original magiskboot lookup order (init_boot.cpio before ramdisk.cpio).
fn extract_ramdisk(ramdisk_image: &RamdiskImage) -> Result<(Cpio, Option<usize>)> {
    if ramdisk_image.is_vendor_ramdisk() {
        let (pos, target) = ramdisk_image
            .iter_vendor_ramdisk()
            .enumerate()
            .find(|e| e.1.get_name_raw() == b"init_boot")
            .or_else(|| {
                ramdisk_image
                    .iter_vendor_ramdisk()
                    .enumerate()
                    .find(|e| e.1.get_name_raw() == b"")
            })
            .ok_or_else(|| anyhow!("No suitable vendor ramdisk entry found"))?;
        let mut buf = Vec::<u8>::new();
        target.dump(&mut buf, false)?;
        Ok((Cpio::load_from_data(&buf)?, Some(pos)))
    } else {
        let mut buf = Vec::<u8>::new();
        ramdisk_image.dump(&mut buf, false)?;
        Ok((Cpio::load_from_data(&buf)?, None))
    }
}

fn enforce_bootimage_version(boot: &BootImage<'_>) -> Result<()> {
    if let BootImageVersion::Android(ver) = boot.get_header().get_version()
        && ver < 3
    {
        bail!("bootimage version {ver} is not supported!")
    }
    Ok(())
}

/// v2.5：**我们自己的**模块条目名 —— `ksuinit` 开机加载的就是它，所以它必须是**真模块**。
const OURS_KSU_ENTRY: &str = "sevenk.ko";

/// v2.5：**官方 KernelSU 唯一认得的**模块条目名。
///
/// 上游 `userspace/ksud/src/boot_patch.rs` 判定“这张镜像已经被 KernelSU 改过”就一行：
///
/// ```text
/// let is_kernelsu_patched = cpio.exists("kernelsu.ko");
/// if !is_kernelsu_patched && cpio.exists("init") { cpio.mv("init", "init.real")?; }
/// cpio.add("init", <官方 ksuinit>);
/// cpio.add("kernelsu.ko", <官方模块>);
/// ```
///
/// 也就是说：**官方只看这个文件名，不看别的标记**（`ksu_config` / `init.real` 都不参与）。
/// 它一旦认为“没改过”，就会把 `init` 挪成 `init.real` —— 而我们的 `init` 是我们的 `ksuinit`
/// （真 init 已经被我们放在 `init.real`），于是真 init 被覆盖、开机后 `ksuinit` 反复 exec 自己
/// → **无限重启**（用户 2026-09-21 实测的那次变砖）。
///
/// 所以 v2.5 起我们打的镜像里**两个名字都放**：`sevenk.ko` 是我们的笔迹，
/// `kernelsu.ko` 是给官方看的“已打过补丁”标记（内容同样是真模块，见 [`patch`]）。
const OFFICIAL_KSU_ENTRY: &str = "kernelsu.ko";

/// ramdisk 里 `init` 的四种状态（B4，v2.5 扩展为四态）。
///
/// 判定目的：绝不能在“**布局不兼容**的别的 root 方案改过”的镜像上做 `init -> init.real`，
/// 更不能直接刷写这种镜像 —— 那会把真正的 init 覆盖掉，直接开不了机（硬砖）。
///
/// ⚠️ v2.5 的关键修正（用户 2026-09-21 明确要求）：**官方 KernelSU 的镜像要允许直接安装**。
///   v2.3 把“有 `kernelsu.ko`”一刀切成 `Foreign` → 连“从官方 KSU 升到我们”也拒了，**太粗**。
///   官方 KernelSU 与本项目**同源**：它的 ramdisk 布局同样是
///   `init`(它的 ksuinit) + `init.real`(真正的 Android init) → 布局**兼容** →
///   我们只替换 `init` + 换掉模块条目、**绝不碰 `init.real`**，就能安全接管。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RamdiskState {
    /// **我们自己的**补丁：ramdisk 里有 `sevenk.ko`。允许直接 `--flash`。
    /// v2.5 起我们打的镜像里**同时**还有一个官方名的 `kernelsu.ko`（内容是真模块的副本），
    /// 那是给官方 KernelSU 看的“已打过补丁”标记，不改变这里的归属判定。
    Ours,
    /// **官方 KernelSU / 本项目 ≤ v0.13.151 老包**：有 `kernelsu.ko`，**且**有 `init.real`。
    /// 布局与我们**同源兼容** → **允许直接安装**：只替换 `init` + 换掉模块条目，
    /// **绝不碰 `init.real`**（那是真正的 Android init）。
    Compatible,
    /// 别人的镜像且**布局不兼容**，两类：
    ///   · 有 `kernelsu.ko` 却**没有** `init.real`：畸形 / 未知布局，叠一层必砖；
    ///   · 只有 `init.real`（自定义 init 间接层）、没有任何 KSU 模块：
    ///     其它 KernelSU 分支 / APatch / 手工改过的 ramdisk
    ///     （Magisk 另有 `is_magisk_patched()` 单独拦）。
    /// `--flash` 一律拒绝；离线打补丁到文件仍然允许。
    Foreign,
    /// 原厂镜像：既没有我们的 `sevenk.ko`，也没有 `kernelsu.ko` / `init.real`。
    Stock,
}

impl RamdiskState {
    fn of(cpio: &Cpio) -> Self {
        // v2.5 定稿的判据 —— **只有 `sevenk.ko` 是我们的笔迹**；`kernelsu.ko` 只用来区分
        // “官方 KSU（布局兼容 → 可接管）”和“别人/畸形（不兼容 → 拒绝）”：
        //   ① 有 `sevenk.ko`                          → `Ours`       （我们打的；v2.5 起还带官方名条目）
        //   ② 有 `kernelsu.ko` **且**有 `init.real`    → `Compatible` （官方 KSU / 我们 ≤v0.13.151 老包）
        //   ③ 有 `kernelsu.ko` 但**没有** `init.real`  → `Foreign`    （畸形/未知布局：叠了必砖）
        //   ④ 只有 `init.real`、没有任何 KSU 模块       → `Foreign`    （别的方案）
        //   ⑤ 都没有                                  → `Stock`      （原厂）
        //
        // 为什么**不能**拿 `kernelsu.ko` 当自己人（保留 v2.3 审计 B 的结论）：
        //   官方 KernelSU 打的镜像里也有这个条目 —— 认它 = 认不出官方镜像 = 认亲/护栏全失效。
        //
        // 为什么我们的镜像里**也**要有 `kernelsu.ko`（v2.5 新增，见 [`OFFICIAL_KSU_ENTRY`]）：
        //   官方 ksud 判定“已打过补丁”的唯一依据就是它；缺了它官方会把我们的 `init`（= 我们的
        //   `ksuinit`）挪去覆盖 `init.real` 里的真 init → 开机死循环。两个条目同时在，
        //   才是“官方不叠我们、我们也能接官方”的双向兼容。
        if cpio.exists(OURS_KSU_ENTRY) {
            Self::Ours
        } else if cpio.exists(OFFICIAL_KSU_ENTRY) {
            if cpio.exists("init.real") {
                Self::Compatible
            } else {
                Self::Foreign
            }
        } else if cpio.exists("init.real") {
            Self::Foreign
        } else {
            Self::Stock
        }
    }

    /// v2.6：给 App 用的**只读**输出词（`ksud boot-info ramdisk-state` 的唯一输出）。
    ///
    /// 刻意用**小写英文单词**而不是本地化文案：这是机器接口（App 解析它决定按钮门控），
    /// 不是给人看的说明；给人看的文案在 App 的 4 语言 `strings.xml` 里。
    const fn as_str(self) -> &'static str {
        match self {
            Self::Ours => "ours",
            Self::Compatible => "compatible",
            Self::Foreign => "foreign",
            Self::Stock => "stock",
        }
    }
}

/// v2.5：拿到一张镜像后，对 `init` / `init.real` 该做哪一步。
///
/// **只有原厂镜像才允许把 `init` 挪成 `init.real`** —— 这一步不可逆：
/// 挪错一次（拿我们的 `ksuinit` 去覆盖 `init.real` 里的真 init）就是开机死循环。
/// 抽成纯函数是为了能在宿主机上钉死这条判据（`patch()` 的完整流程要在 Android 上跑）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
enum InitDisposition {
    /// 原厂镜像：`init` 挪成 `init.real`（唯一允许这一挪的情形）
    MoveInitToReal,
    /// 真 init 已经在 `init.real` 里：**原样保留，一个字节都不碰**
    /// （我们自己的镜像、官方 KernelSU 的镜像、以及不兼容的别人的镜像都走这条）
    KeepInitReal,
}

/// v2.5：`init` 处置判据 —— **`Compatible` 绝不能挪 `init`**（挪了就是上次那个死循环）。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn init_disposition(state: RamdiskState) -> InitDisposition {
    match state {
        RamdiskState::Stock => InitDisposition::MoveInitToReal,
        RamdiskState::Ours | RamdiskState::Compatible | RamdiskState::Foreign => {
            InitDisposition::KeepInitReal
        }
    }
}

/// B1：这份备份要不要登记成 ramdisk 里的 `stock_image.sha1`（原厂备份）？
///
/// **只看镜像本身是不是原厂态**（`RamdiskState::Stock`）。
/// 显式 `--backup`（`explicit_backup`）只决定“要不要做备份”，
/// **不决定这份备份算不算原厂** —— 在“已经打过补丁的镜像”上勾了强制备份，那份镜像
/// **不是原厂**；把它登记进去会让 `stock_image.sha1` 指向一个已打补丁的镜像，
/// **直接毁掉 `restore` 的退路**（v2.2 的 🟠：旧写法是 `backup || ramdisk_state == Stock`）。
///
/// 抽成纯函数是为了让宿主机单测能钉死这条判据（`patch()` 本体在 `#[cfg(target_os = "android")]` 里）。
// 只在 Android 的刷写路径里使用；宿主机（非 test）构建会算“未使用”，由单测覆盖判据。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn should_record_stock(ramdisk_state: RamdiskState, explicit_backup: bool) -> bool {
    if explicit_backup && ramdisk_state != RamdiskState::Stock {
        // 用户显式要求备份：备份**照做**（回滚要用），但绝不登记成“原厂备份”。
        println!(
            "- Explicit --backup: keeping this backup, but NOT recording it as the stock image \
             (the current image is not stock)"
        );
    }
    ramdisk_state == RamdiskState::Stock
}

/// B2：镜像长度必须装得进目标分区（防“选错分区”）。
///
/// 抽成纯函数是因为 `flash_partition` 要 ioctl 真块设备、宿主机测不了；
/// 而“长度对不上就拒绝写”是最致命的判据之一，必须能被单测钉死。
// 只在 Android 的刷写路径里使用；宿主机（非 test）构建会算“未使用”，由单测覆盖判据。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn image_fits_partition(image_len: u64, partition_size: u64) -> bool {
    image_len <= partition_size
}

/// B5：没有 ramdisk 的镜像不允许 `--flash`。
///
/// 能直接刷的 boot/init_boot 必然有 ramdisk；没有 ramdisk 多半是选错了分区
/// （vendor_boot / 别的分区）。打补丁到文件仍然允许（AVD / 手工场景要用）。
// 只在 Android 的刷写路径里使用；宿主机（非 test）构建会算“未使用”，由单测覆盖判据。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn must_refuse_no_ramdisk_flash(flash: bool, has_ramdisk: bool) -> bool {
    flash && !has_ramdisk
}

/// 🔴1（v2.4）：**运行中的内核是不是我们家的**（命令行自检的结果）。
///
/// ⚠️ **v2.6 起这不再是拒绝的依据**，只作为**附加信息**打印（见 [`report_kernel_owner`]）。
///    判据已经从"内核是不是我们的"改成"**目标镜像能不能安全打**"
///    （见 [`must_refuse_unsafe_flash`]），理由见那个函数的长注释。
///
/// 保留这个枚举是因为它仍然是排障时最有用的信息之一：
///   · `Ours`   = 内核里能读到 `KSU_IOCTL_STEALTH_GET_G`（cmd 27，本 fork 独有的只读安全阀）；
///   · `Foreign`= 官方 KernelSU / 其它 fork / 面具 / APatch / 根本没有 ksu 驱动。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
enum KernelOwner {
    /// 我们家的内核（至少是带「断代闸门安全阀」的那一代）。
    Ours,
    /// 不是我们的：官方 KernelSU / 其它 fork / 面具 / APatch / 根本没有 ksu 驱动。
    Foreign,
}

/// v2.6：**写分区的唯一判据 ＝ 目标镜像能不能安全打**（不再看运行中的内核是谁的）。
///
/// ══════════════════════════════════════════════════════════════════
/// 为什么把判据从"内核归属"换成"镜像状态"（用户 2026-09-21 明确要求）
/// ══════════════════════════════════════════════════════════════════
/// 旧判据（v2.4/v2.5）是"**内核不是我们的 + 要写分区 → 一律拒**"，
/// 它的代价是：设备上跑着**官方 KernelSU** 时，用户想"换回我们的包"点**直接安装**，
/// `ksud boot-patch -f` 会被自己拒掉 —— 而这恰恰是用户要的那条路（用户在官方 KSU 上升级过来）。
///
/// 真正决定"会不会变砖"的**从来不是运行中的内核**，而是**目标镜像的 ramdisk 布局**：
///   · `Ours`（有 `sevenk.ko`）：我们自己打的，当然能再打一层（幂等覆盖）；
///   · `Compatible`（有 `kernelsu.ko` **且**有 `init.real`）：官方 KernelSU / 我们 ≤v0.13.151
///     老包的布局，**与我们同源** —— 真 init 已经安全地待在 `init.real` 里，
///     我们只替换 `init`（换成我们的 ksuinit）+ 换掉模块条目，**`init.real` 一个字节都不碰**
///     （判据见 [`init_disposition`]，离线演练用 sha256 逐字节证过）→ 打出来能开机；
///   · `Stock`（既无 `sevenk.ko` 也无 `kernelsu.ko`/`init.real`）：原厂镜像，标准路径；
///   · **`Foreign`**（有 `kernelsu.ko` 但**没有** `init.real` 的畸形布局 / 只有 `init.real`
///     的别的方案）：布局不兼容 —— 在这种镜像上做 `init -> init.real` 会**覆盖真 init**，
///     就是用户实测过的那个无限重启砖。**这一类才拒**。
///
/// 所以"内核归属"从**判据**降级为**文案**：`report_kernel_owner()` 照旧打印，
/// 但写不写分区由上面这张表决定。`--force-foreign` 逃生开关保留（正常情况不再需要）。
///
/// 抽成纯函数的理由和 B1~B5 一样：真正要 ioctl 的那步在 Android 上、宿主机测不了，
/// 而"什么情况下必须拒绝写分区"是最致命的判据之一，必须能被单测钉死。
///
/// 注意 `write_partition`（而不是 `flash`）：这样"离线打补丁到文件"永远是 `false`，
/// **一条只写文件的安全退路永远不会被这道闸拦住**（见任务要求）。
// 只在 Android 的刷写路径里使用；宿主机（非 test）构建会算“未使用”，由单测覆盖判据。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn must_refuse_unsafe_flash(
    state: RamdiskState,
    write_partition: bool,
    force_foreign: bool,
) -> bool {
    write_partition && !force_foreign && matches!(state, RamdiskState::Foreign)
}

/// 🟠2（v2.4）/ v2.5：`restore` / `rebuild_without_ksu` 的**认亲判据**。
///
/// v2.5 起**放宽到 `Ours` 或 `Compatible` 都允许**（用户 2026-09-21 要求）：
///   · `Ours`（有 `sevenk.ko`）：这是我们打的，当然允许还原；
///   · `Compatible`（有 `kernelsu.ko` + `init.real`，即官方 KernelSU / 我们 ≤v0.13.151 老包）：
///     布局与我们**同源兼容** —— 拆掉模块条目、把 `init.real` 放回 `init`，
///     结果就是一张干净的、能开机的原厂镜像，**不会**把设备搞砖。
///
/// `Foreign`（布局不兼容 / 别的方案）仍然拒 —— 对它 `restore` 是**拆别人的 root**，
/// 且布局不明，容易出事。`Stock` 不算"别人的"，但也**不是我们的** → 报"没打过补丁"。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn is_restorable(state: RamdiskState) -> bool {
    matches!(state, RamdiskState::Ours | RamdiskState::Compatible)
}

/// 🟠2（v2.4）：**布局不兼容的镜像不许 restore**（除非显式 `--force-foreign`）。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn must_refuse_foreign_restore(state: RamdiskState, force_foreign: bool) -> bool {
    matches!(state, RamdiskState::Foreign) && !force_foreign
}

/// 🟠1（v2.8）：`restore` 全流程的**准入闸** —— 从 `restore()` 里原样搬出来，
/// 让"入口那道"和"`rebuild_without_ksu` 里那道纵深防御"共用同一份接线。
///
/// 两道，顺序就是 `restore()` 里的顺序：
///   ① `Foreign` 且**没有** `--force-foreign` → 拒（布局不兼容，拆它可能开不了机）；
///   ② 不是"我们打的 / 官方 KSU 同源布局"（`Foreign` 或 `Stock`）且没有 `--force-foreign` → 拒
///      （`Stock` 只是"没打过补丁"，不属于"别人的"，所以文案不同）。
///
/// 显式 `--force-foreign`（DANGEROUS，帮助文本里写着 advanced users only）时**两道都放行** ——
/// 这正是这个开关存在的意义：v2.4 之前它只被第①道看见、被第②道无条件拦住 = 死开关。
///
/// 判据本身（[is_restorable] / [must_refuse_foreign_restore]）一个字没改，
/// 改的只是"接线"；没加开关时**一刀不放**（fail-closed，与 v2.5 行为完全一致）。
fn restore_admission(state: RamdiskState, force_foreign: bool) -> Result<()> {
    if must_refuse_foreign_restore(state, force_foreign) {
        bail!("{}", foreign_restore_message());
    }
    if !force_foreign && !is_restorable(state) {
        bail!(
            "boot image is not patched by 7kimisu or official KernelSU \
             (no sevenk.ko / kernelsu.ko entry); nothing to restore\n\
             · pass `--force-foreign` to restore anyway (DANGEROUS, advanced users only)"
        );
    }
    Ok(())
}

/// 🟠2（v2.4）：给"不兼容的镜像"准备的拒绝文案（v2.5 去掉了"官方 KSU"这一条 ——
/// 官方 KSU 现在是 `Compatible`、允许直接安装与还原）。
///
/// 单独抽出来是因为这句话要**点名**剩下那两类，用户在命令行里一眼就知道自己踩的是哪一类。
fn foreign_restore_message() -> String {
    "refusing to restore: this boot image was NOT patched by KernelSU-compatible tools.\n\
     · a `kernelsu.ko` entry with NO `init.real` (malformed / unknown layout) — stacking on it bricks;\n\
     · another root solution left an `init.real` entry.\n\
     Restoring it here would DELETE that solution's modules and move `init.real` back to\n\
     `init` — i.e. it would tear down a root you did not install. Nothing was written.\n\
     If you really want to remove the other solution, flash the STOCK boot/init_boot image for\n\
     this device from a computer (fastboot) instead.\n\
     Advanced users / debugging: re-run with --force-foreign (DANGEROUS — you have been warned)."
        .to_owned()
}

/// 🔴1（v2.4）/ v2.6：`--flash` 之前的**内核自检**（Android 专用，因为只有 Android 能写分区）。
///
/// 【判据为什么可靠】不是猜版本号、也不是猜文件名，而是**直接问内核**：
///   走 `ksucalls::stealth_probe_get()` —— 也就是本 fork **独有**的只读超级调用
///   `KSU_IOCTL_STEALTH_GET_G`（cmd 27，见 `uapi/supercall.h` 注释"断代闸门安全阀"）。
///   · 官方 KernelSU / APatch 的内核里**没有** cmd 27 → 内核返回 `-ENOTTY`；
///   · 面具 / 完全没有 root → 连 `[ksu_driver]` 句柄都拿不到；
///   · 只有我们的内核会成功回话（该命令对 root 放行，见 `kernel/supercall/perm.c`）。
///
/// ⚠️ v2.6：**这个结论不再决定拒绝**，只用来打印信息（见 [`report_kernel_owner`]）。
///   拒绝与否由 [`must_refuse_unsafe_flash`] 按**目标镜像状态**判。
#[cfg(target_os = "android")]
fn detect_kernel_owner() -> KernelOwner {
    match crate::ksucalls::stealth_probe_get() {
        Ok(_) => KernelOwner::Ours,
        Err(_) => KernelOwner::Foreign,
    }
}

/// v2.6：把内核归属**作为信息**打印出来（不再是闸）。
///
/// 旧版这里叫 `ensure_kernel_owner_write_guard`，副作用是**拒写分区**；
/// 现在只打印，理由见 [`must_refuse_unsafe_flash`] 的长注释（用户 2026-09-21 的要求：
/// 官方 KernelSU 的内核上必须能点"直接安装"）。
///
/// 仍然**只在要写分区时**才探测 —— 离线打补丁（不带 `-f`）只产出文件、不碰任何分区，
/// 连一个 ioctl 都不做（少一次内核往返，也少一条失败路径）。
#[cfg(target_os = "android")]
fn report_kernel_owner(write_partition: bool) {
    if !write_partition {
        return;
    }
    match detect_kernel_owner() {
        KernelOwner::Ours => println!("- Kernel self-check: 7kimisu kernel detected"),
        KernelOwner::Foreign => {
            let info = crate::ksucalls::get_info();
            println!("- Kernel self-check: NOT a 7kimisu kernel (informational only)");
            println!(
                "-   kernel reports uapi={} version={} flags=0x{:x}",
                info.uapi_version, info.version, info.flags
            );
            println!("-   writing is decided by the TARGET IMAGE layout below, not by the kernel");
        }
    }
}

/// v2.6：**只读**查询一张 boot 镜像的 ramdisk 状态（给 App 的 `boot-info ramdisk-state` 用）。
///
/// ══════════════════════════════════════════════════════════════════
/// 契约（App 侧按这个解析，`RootOwnership.parseRamdiskState`）
/// ══════════════════════════════════════════════════════════════════
/// 成功 → 恰好打印一个词并换行：`ours` / `compatible` / `foreign` / `stock`；
/// 任何失败（不是 Android 且没给 `--boot` / 分区读不到 / 镜像畸形 / 没有 ramdisk /
/// 没 root 拿不到块设备）→ 打印 `unknown`。
///
/// **绝不写盘**：只 `open` + `mmap` 只读 + 内存里解包，连备份目录都不碰。
/// 失败时返回 `unknown` 而不是报错退出，是为了让 App 端只有一条"拿不准"的路径 ——
/// 而 App 对 `unknown` 是 **fail-closed**（不显示"直接安装"按钮）。
pub fn query_ramdisk_state(image: Option<&Path>) -> &'static str {
    let Some(path) = resolve_query_image(image) else {
        // 宿主机上没有"当前设备的分区"可问 —— 必须显式给 --boot。
        #[cfg(not(target_os = "android"))]
        println!("- ramdisk-state: --boot <image> is required on the host");
        return "unknown";
    };

    match read_ramdisk_state(&path) {
        Ok(state) => state.as_str(),
        Err(e) => {
            // 诊断行不影响解析：App 只认**最后一行**的非空词，
            // 而这里最终仍然打印 `unknown`（见 `RootOwnership.parseRamdiskState`）。
            println!("- ramdisk-state: cannot read {}: {e:#}", path.display());
            "unknown"
        }
    }
}

/// v2.6：把"要查哪张镜像"定下来（Android 不给 `--boot` 就自动找当前 boot 分区）。
///
/// 返回 `None` = 没有可查的镜像 → 调用方按 `unknown` 处理（fail-closed）。
#[cfg(target_os = "android")]
fn resolve_query_image(image: Option<&Path>) -> Option<PathBuf> {
    image.map_or_else(
        || {
            let kmi = get_current_kmi().unwrap_or_default();
            Some(auto_boot_partition_path(&kmi, false, false, &None))
        },
        |p| Some(p.to_path_buf()),
    )
}

/// v2.6：宿主机上没有"当前设备的分区"，只能显式给路径（离线核对用）。
#[cfg(not(target_os = "android"))]
fn resolve_query_image(image: Option<&Path>) -> Option<PathBuf> {
    image.map(Path::to_path_buf)
}

/// v2.6：把"读一张镜像 → 判 ramdisk 状态"抽出来（只读；[`query_ramdisk_state`] 的核）。
fn read_ramdisk_state(path: &Path) -> Result<RamdiskState> {
    let data = map_file(path)?;
    let image = BootImage::parse(&data)?;
    let ramdisk = image
        .get_blocks()
        .get_ramdisk()
        .ok_or_else(|| anyhow!("no ramdisk in this image"))?;
    let (cpio, _vendor_idx) = extract_ramdisk(ramdisk)?;
    Ok(RamdiskState::of(&cpio))
}

/// B3：刷写失败后该怎么收场的**判据**（纯逻辑，宿主机可测）。
///
/// `flash_partition` 要 ioctl 真块设备、宿主测不了，所以把判据抽出来：
///   · 有备份**且**回滚写回 + 再验 sha1 都成功 → 分区=原样，设备应仍能开机；
///   · 其余一切情况（无备份 / 回滚失败 / 回滚后 sha1 对不上）→
///     分区可能已损坏，必须明确要求“请勿重启，用电脑 fastboot 刷回备份”。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FlashFailureAction {
    /// 已回滚成功：可以告诉用户“设备应仍能开机”。
    RolledBack,
    /// 没退路：必须报“分区可能已损坏，请勿重启”。
    PartitionMayBeCorrupted,
}

// 只在 Android 的刷写路径里使用；宿主机（非 test）构建会算“未使用”，由单测覆盖判据。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const fn flash_failure_action(has_backup: bool, rollback_succeeded: bool) -> FlashFailureAction {
    if has_backup && rollback_succeeded {
        FlashFailureAction::RolledBack
    } else {
        FlashFailureAction::PartitionMayBeCorrupted
    }
}

/// 取内核版本号的 `x.y.z` 前缀（B6 的核心规则：只有这个前缀相同才允许强行加载模块）。
///
/// 输入是 `/proc/version` 或模块 vermagic 里的那一小段，形如
/// `5.15.149-android13-8-g1234`；后面跟的 `-android13-...`、`SMP`、`preempt`、
/// `mod_unload`、`modversions`、架构标签、构建后缀一律**不参与**比较。
fn kernel_version_prefix(text: &str) -> Option<String> {
    let token = text.split_whitespace().next()?;
    let mut numbers = [0u32; 2];
    let mut parts = token.split('.');
    for number in &mut numbers {
        let segment = parts.next()?;
        let digits: String = segment.chars().take_while(char::is_ascii_digit).collect();
        if digits.is_empty() {
            return None;
        }
        *number = digits.parse().ok()?;
    }
    // 只要"主.次"（= Android 的 KMI）；第三段 sublevel 允许不同，见本函数上方与
    // `ensure_module_matches_running_kernel` 的说明。
    Some(format!("{}.{}", numbers[0], numbers[1]))
}

/// 从 `/proc/version` 的整行里取 `x.y.z`。
/// 该文件形如 `Linux version 5.15.149-android13-8-g1234 (builder@host) #1 SMP ...`。
fn proc_version_prefix(text: &str) -> Option<String> {
    let after = text.split_once("version ").map_or(text, |(_, rest)| rest);
    kernel_version_prefix(after)
}

/// 读运行内核的 `x.y.z`。读不到 `/proc/version`（非 Android 主机 / 特殊环境）时返回 None，
/// 调用方据此**优雅跳过**检查，保证开发机上的单测和桌面构建不受影响。
fn running_kernel_version_prefix() -> Option<String> {
    let text = std::fs::read_to_string("/proc/version").ok()?;
    proc_version_prefix(&text)
}

/// 在 `.ko` 的字节里找出 `.modinfo` 段里的 `vermagic=`（B6）。
///
/// 这里故意不做完整 ELF 解析：`vermagic=` 是模块里唯一的、以 NUL 结尾的明文条目，
/// 直接扫一遍几百 KB 的字节最省事，也避免给 ksud 新加一个 ELF 解析依赖。
fn find_module_vermagic(ko: &[u8]) -> Option<String> {
    const TAG: &[u8] = b"vermagic=";
    let mut from = 0usize;

    while let Some(relative) = ko
        .get(from..)?
        .windows(TAG.len())
        .position(|window| window == TAG)
    {
        let value_start = from + relative + TAG.len();
        let value_end = ko.get(value_start..)?.iter().position(|byte| *byte == 0)?;
        if let Ok(value) = std::str::from_utf8(&ko[value_start..value_start + value_end])
            && kernel_version_prefix(value).is_some()
        {
            return Some(value.to_owned());
        }
        from = value_start + value_end;
    }

    None
}

/// B6.1：打包 `.ko` 之前，确认模块 vermagic 里的内核版本与**运行中的内核**同一代。
///
/// 规则（和 ksuinit 侧的护栏完全一致）：**KMI = 主.次（如 `6.12`）必须完全相同**；
/// 允许不同的只有 **sublevel（第三段）** 和尾部组件
///（SMP / preempt / mod_unload / modversions / 架构标签 / 构建后缀）。
///
/// ⚠️ 为什么是"主.次"而不是"主.次.修"（2026-09-21 实测修正）：
///   我们内置的 `.ko` 是**按 KMI 预编译**的通用模块（文件名就叫 `android16-6.12_sevenk.ko`，
///   `assets::get_asset("{kmi}_sevenk.ko")` 也是**按 KMI 选**的），它的构建版本号
///   （实测 `android16-6.12_sevenk.ko` = **6.12.76**）与用户设备内核的 patchlevel
///   （本项目那台平板是 **6.12.30**）**本来就不会相等**。
///   如果要求 `x.y.z` 全等，结果就是"**谁都装不了内核**" —— 这是必须避免的功能回归。
///   而"主.次相同"正是 Android KMI 的兼容口径，也正是任务书给的兜底口径
///   （「至少限制到仅 sublevel 差异」）。真正防 panic 的那道闸在 ksuinit（运行期加载前）。
///
/// 只在 Android 上生效；开发机 / 桌面构建一律跳过（`/proc/version` 也不保证存在）。
fn ensure_module_matches_running_kernel(ko: &[u8]) -> Result<()> {
    if ko.is_empty() {
        // `--no-install`：不打包模块，没什么可检查的
        return Ok(());
    }

    if !cfg!(target_os = "android") {
        println!("- Not on Android, skip the kernel/module match check");
        return Ok(());
    }

    let Some(running) = running_kernel_version_prefix() else {
        println!("- Cannot read the running kernel version, skip the kernel/module match check");
        return Ok(());
    };

    let vermagic = find_module_vermagic(ko).ok_or_else(|| {
        anyhow!(
            "cannot read vermagic from the kernel module; \
             refusing to continue without a kernel/module same-source check"
        )
    })?;
    let module = kernel_version_prefix(&vermagic).ok_or_else(|| {
        anyhow!("cannot parse the kernel version from module vermagic {vermagic:?}")
    })?;

    ensure!(
        module == running,
        "kernel/module mismatch: this module is built for the Linux {module} KMI but the device runs Linux {running}.\n\
         Loading a module from a different kernel generation could panic the kernel (hard brick),\n\
         so patching/flashing has been refused.\n\
         Use a module built for this device's kernel generation (KMI)."
    );
    println!("- Kernel module matches the running kernel generation (KMI {running})");
    Ok(())
}

#[allow(clippy::struct_excessive_bools)]
#[derive(clap::Args, Debug)]
pub struct BootPatchArgs {
    /// boot image path, if not specified, will try to find the boot image automatically
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// kernel image path to replace
    #[arg(short, long)]
    pub kernel: Option<PathBuf>,

    /// LKM module path to replace, if not specified, will use the builtin one
    #[arg(short, long)]
    pub module: Option<PathBuf>,

    /// init to be replaced
    #[arg(short, long)]
    pub init: Option<PathBuf>,

    /// will use another slot when boot image is not specified
    #[cfg(target_os = "android")]
    #[arg(short = 'u', long, default_value = "false")]
    pub ota: bool,

    /// Flash it to boot partition after patch
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = "false")]
    pub flash: bool,

    /// 【DANGEROUS】Allow --flash even when the target image layout is INCOMPATIBLE
    /// (`Foreign`: a `kernelsu.ko` without `init.real`, or another root solution's `init.real`).
    /// Normally NOT needed any more: official-KernelSU images are compatible and allowed.
    #[cfg(target_os = "android")]
    #[arg(long, default_value = "false")]
    pub force_foreign: bool,

    /// Force backup source image as stock image
    #[cfg(target_os = "android")]
    #[arg(long, default_value = "false")]
    pub backup: bool,

    /// Output path. If not specified, will use current directory.
    /// If specified, the boot image will be written to the directory
    /// even if --flash is specified.
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// Output path. If not specified, will use current directory.
    #[cfg(not(target_os = "android"))]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// KMI version, if specified, will use the specified KMI
    #[arg(long, default_value = None)]
    pub kmi: Option<String>,

    /// target partition override (init_boot | boot | vendor_boot)
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub partition: Option<String>,

    /// File name of the output. If specified, the boot image will be
    /// written to the output directory even if --flash is specified.
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// File name of the output.
    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// Extra cmdline to append to boot image header
    #[arg(long, default_value = None)]
    pub cmdline: Option<String>,

    /// Always allow shell to get root permission
    #[arg(long, default_value = "false")]
    allow_shell: bool,

    /// Force enable adbd and disable adbd auth
    #[arg(long, default_value = "false")]
    enable_adbd: bool,

    /// Add more adb_debug prop
    #[arg(long, required = false)]
    adb_debug_prop: Option<String>,

    /// Do not (re-)install kernelsu, only modify configs (allow_shell, etc.)
    #[arg(long, default_value = "false")]
    no_install: bool,

    /// Do not load custom rc
    #[arg(long, default_value = "false")]
    no_custom_rc: bool,

    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = "aarch64")]
    arch: String,

    /// Patching ramdisk instead of boot image. This is used for AVD ramdisk
    #[arg(long, default_value = "false")]
    ramdisk: bool,
}

pub fn patch(args: BootPatchArgs) -> Result<()> {
    let inner = move || {
        let BootPatchArgs {
            boot: image,
            init,
            kernel,
            module: kmod,
            out,
            kmi,
            out_name,
            cmdline,
            allow_shell,
            enable_adbd,
            adb_debug_prop,
            no_install,
            #[cfg(target_os = "android")]
            ota,
            #[cfg(target_os = "android")]
            flash,
            #[cfg(target_os = "android")]
            force_foreign,
            #[cfg(target_os = "android")]
            backup,
            #[cfg(target_os = "android")]
            partition,
            no_custom_rc,
            #[cfg(not(target_os = "android"))]
            arch,
            ramdisk,
        } = args;

        println!(include_str!("banner"));

        // 🔴1（v2.4）/ v2.6：**内核自检 —— 现在只是"信息"**。
        // 位置仍在最前面（任何解包 / 备份 / 写盘之前）：它会打印"这台机器上的 root 是谁给的"，
        // 排障时一眼就能看到；但**不再拒绝**。真正决定写不写分区的判据在后面 ——
        // 等镜像解包出来、`RamdiskState::of()` 有结论之后再判（见下面的 `must_refuse_unsafe_flash`）。
        // 为什么必须先解包才能判：能不能安全打，取决于**目标镜像的 ramdisk 布局**，
        // 而不是运行中的内核（用户 2026-09-21 的要求，详见 `must_refuse_unsafe_flash` 注释）。
        #[cfg(target_os = "android")]
        report_kernel_owner(flash);

        #[cfg(target_os = "android")]
        let patch_file = image.is_some();

        #[cfg(target_os = "android")]
        if !patch_file {
            ensure_gki_kernel()?;
        }

        let is_replace_kernel = kernel.is_some();

        if ramdisk && is_replace_kernel {
            bail!("incompatiable option: --ramdisk and --kernel")
        }

        #[cfg(target_os = "android")]
        if ramdisk && flash {
            bail!("incompatiable option: --ramdisk and --flash")
        }

        if is_replace_kernel {
            ensure!(
                init.is_none() && kmod.is_none(),
                "init and module must not be specified."
            );
        }

        // None means --no-install: preserve the marker for the existing LKM.
        let bundled_lkm = (!no_install).then_some(kmod.is_none());

        let kmi = kmi.map_or_else(
            || -> Result<_> {
                if kmod.is_some() {
                    return Ok(String::new());
                }
                #[cfg(target_os = "android")]
                if ota {
                    let slot_suffix = get_slot_suffix(true);
                    println!("- Trying to auto detect KMI version from boot");
                    return parse_kmi_from_boot(Path::new(&format!(
                        "/dev/block/by-name/boot{slot_suffix}"
                    )));
                }
                #[cfg(target_os = "android")]
                match get_current_kmi() {
                    Ok(value) => {
                        return Ok(value);
                    }
                    Err(e) => {
                        println!("- {e}");
                    }
                }
                Ok(if ramdisk {
                    bail!("please specify kmi manually")
                } else if let Some(image_path) = &image {
                    println!(
                        "- Trying to auto detect KMI version for {}",
                        image_path.display()
                    );
                    parse_kmi_from_boot(image_path)?
                } else if let Some(kernel_path) = &kernel {
                    println!(
                        "- Trying to auto detect KMI version for {}",
                        kernel_path.display()
                    );
                    parse_kmi_from_kernel(kernel_path)?
                } else {
                    String::new()
                })
            },
            Ok,
        )?;

        let boot_image_file = if let Some(image) = image {
            ensure!(image.exists(), "boot image not found");
            std::fs::canonicalize(image)?
        } else {
            #[cfg(target_os = "android")]
            {
                auto_boot_partition_path(&kmi, ota, is_replace_kernel, &partition)
            }
            #[cfg(not(target_os = "android"))]
            {
                bail!("Please specify a boot image");
            }
        };

        #[cfg(target_os = "android")]
        println!("- Bootdevice: {}", boot_image_file.display());

        // try extract bootctl/busybox
        #[cfg(target_os = "android")]
        let _ = assets::ensure_binaries(false);

        println!("- Preparing assets");
        println!("- Unpacking boot image");
        let boot_image_data = map_file(&boot_image_file)?;
        let boot_image = if ramdisk {
            BootImage::parse_raw_ramdisk(&boot_image_data)?
        } else {
            BootImage::parse(&boot_image_data)?
        };
        enforce_bootimage_version(&boot_image)?;

        let mut patcher = BootImagePatchOption::new(&boot_image);

        if let Some(cmdline_value) = &cmdline {
            patcher.override_cmdline(cmdline_value.as_bytes());
            println!("- Set cmdline to: {cmdline_value}");
        }

        if let Some(kernel_path) = kernel {
            println!("- Adding Kernel");
            let kernel_data = map_file(&kernel_path)?;
            patcher.replace_kernel(Box::new(Cursor::new(kernel_data)), false);
        }

        let kernelsu_ko: Box<dyn AsRef<[u8]>> = if no_install {
            Box::new(Vec::<u8>::new())
        } else if let Some(kmod_path) = kmod {
            Box::new(map_file(&kmod_path)?)
        } else {
            #[cfg(target_os = "android")]
            {
                println!("- KMI: {kmi}");
                let name = format!("{kmi}_sevenk.ko");
                assets::get_asset(&name).with_context(|| format!("Failed to load {name}"))?
            }
            #[cfg(not(target_os = "android"))]
            {
                println!("- KMI: {kmi}");
                println!("- Arch: {arch}");
                let name = format!("{arch}/{kmi}_sevenk.ko");
                assets::get_asset(&name).with_context(|| format!("Failed to load {name}"))?
            }
        };

        let ksu_init: Box<dyn AsRef<[u8]>> = if no_install {
            Box::new(Vec::<u8>::new())
        } else if let Some(init_path) = init {
            Box::new(map_file(&init_path)?)
        } else {
            #[cfg(not(target_os = "android"))]
            {
                assets::get_asset(&format!("{arch}/ksuinit")).context("Failed to load ksuinit")?
            }
            #[cfg(target_os = "android")]
            {
                assets::get_asset("ksuinit").context("Failed to load ksuinit")?
            }
        };

        let (mut cpio, vendor_ramdisk_idx) = if let Some(ramdisk_image) =
            boot_image.get_blocks().get_ramdisk()
        {
            extract_ramdisk(ramdisk_image)?
        } else {
            // B5：没有 ramdisk 的镜像（多半是 vendor_boot / 别的分区）不可能是 --flash 的目标。
            // 打补丁到文件时仍然允许（AVD / 手工场景要用）。
            #[cfg(target_os = "android")]
            ensure!(
                !must_refuse_no_ramdisk_flash(flash, false),
                "the target image has NO ramdisk, but --flash was requested.\n\
                 A partition that can be flashed directly (boot/init_boot) must contain a ramdisk;\n\
                 the wrong partition was probably selected. Use --partition to pick the right one,\n\
                 or patch to a file instead."
            );
            println!("- No ramdisk, create by default");
            (Cpio::new(), None)
        };

        // 刷写前备份的落盘路径（B1/B3）：校验失败时要拿它回滚
        #[cfg(target_os = "android")]
        let mut flash_backup: Option<PathBuf> = None;

        // v2.6：**先在纯判据层给这张镜像定性**（Ours / Compatible / Foreign / Stock）。
        //
        // 刻意放在 `if !no_install` **外面**：`--no-install --flash` 同样要写分区，
        // 也就同样要过下面那道闸（旧写法把它放在 `!no_install` 里，`--no-install` 能绕过）。
        let ramdisk_state = RamdiskState::of(&cpio);

        // v2.6：**写分区的唯一闸** ＝ "这张镜像能安全打吗"（不再看内核归属）。
        //   ✅ `Ours` / `Compatible` / `Stock` → 放行（`Compatible` 就是官方 KernelSU 的镜像，
        //      用户 2026-09-21 要的"在官方 KSU 上直接安装"走的就是这条）；
        //   ❌ `Foreign`（布局不兼容：无 `init.real` 的 `kernelsu.ko` / 只有 `init.real`）→ 拒。
        // 打补丁到文件（离线，`flash=false`）永远放行 —— 那是安全退路。
        // 放在这里（而不是更早）是因为判据需要镜像已解包；但仍**远在**任何写盘动作之前
        // （备份在下面、分区写在更后面），拒绝时不留下任何副作用。
        #[cfg(target_os = "android")]
        if must_refuse_unsafe_flash(ramdisk_state, flash, force_foreign) {
            bail!(
                "the target boot image was patched by an INCOMPATIBLE root solution, so\n\
                 writing it has been refused.\n\
                 · a `kernelsu.ko` entry with NO `init.real` (malformed / unknown layout); or\n\
                 · another root solution — it left an `init.real` entry.\n\
                 Stacking a 7kimisu patch on such an image could overwrite the real init and\n\
                 leave the device unbootable. NOTHING was written; the partition is untouched.\n\
                 \n\
                 What to do (both are safe, and neither touches the running partition):\n\
                 1) patch the STOCK boot/init_boot image for this exact device to a FILE\n\
                    (\"Select a file and patch\" in the manager, or\n\
                     ksud boot-patch -b <stock_boot.img> -o <output_dir>),\n\
                    then flash that file yourself (fastboot / a flashing tool); or\n\
                 2) restore the stock boot image first, then use \"Direct install\".\n\
                 \n\
                 Note: a device running OFFICIAL KernelSU (or 7kimisu <= v0.13.151) is NOT\n\
                 refused any more — its layout (`init` + `init.real` + `kernelsu.ko`) is\n\
                 compatible, so \"Direct install\" works there.\n\
                 \n\
                 Advanced users / debugging only: re-run with --force-foreign to bypass this\n\
                 check (DANGEROUS: it can brick the device; you have been warned)."
            );
        }

        if !no_install {
            ensure!(
                !cpio.is_magisk_patched(),
                "Cannot work with Magisk patched image"
            );

            println!("- Adding KernelSU LKM");
            // v2.5（双向兼容）：我们打的镜像里**放两个模块条目**。
            //   · `sevenk.ko`（[`OURS_KSU_ENTRY`]）：**我们自己的**笔迹，`ksuinit` 开机加载它，
            //     所以**必须是真模块**；判定归属也只看它。
            //   · `kernelsu.ko`（[`OFFICIAL_KSU_ENTRY`]）：**官方 KernelSU 唯一认得的**名字。
            //     官方 ksud 看到它才会认为“这张镜像已经被 KernelSU 改过”，从而**不再**做
            //     `mv init -> init.real`（那一步会把 `init.real` 里的真 init 覆盖掉 → 无限重启）。
            //     2026-09-20 改名（只留 `sevenk.ko`）就是这次变砖的根因，v2.5 把官方名补回来。
            //
            // 这两个条目放**真模块**（不是空文件/文本标记）：官方真来打补丁时会把 `kernelsu.ko`
            // **整个换成它自己的模块**（`cpio.add("kernelsu.ko", <官方模块>)`），所以正常流程里
            // 我们这一份永远不会被 `init_module`；唯一会加载 `/kernelsu.ko` 的是**官方 ksuinit**，
            // 而它只有当 `init` 已经被官方换成官方 ksuinit 时才会跑 —— 那一刻这一条已经是官方的了。
            // 退一万步真被加载，也是**同 KMI、合法的** KSU 模块（打包前由 B6.1 校验过），
            // 而不是一个会让内核报错的垃圾文件；官方 ksuinit 另有 `has_kernelsu()` 提前返回。
            //
            // B4（v2.5 四态）：别再用一个 bool 混过去 ——
            // Ours / Compatible / Foreign / Stock 的处置完全不同。
            // ⚠️ v2.6：`ramdisk_state` 已经提到 `if !no_install` **外面**算了（写分区闸要用它），
            //    这里只是把它清掉之后继续用同一个值 —— **不要**在这里重算
            //    （重算会看不到"官方名那条已被移除"，把 Compatible 看成 Foreign）。
            //
            // 官方名那条（可能是官方 KSU 或我们 ≤v0.13.151 老包留下的真模块）先清掉，
            // 稍后统一写回我们自己的副本，避免 ramdisk 里留着别人的模块。
            cpio.rm(OFFICIAL_KSU_ENTRY, false);

            // v2.5：`init` 的处置走**纯判据** —— 只有原厂镜像才允许 `mv init -> init.real`。
            // `Compatible`（官方 KSU）走 `KeepInitReal`：真 init 就在 `init.real` 里，一个字节都不碰。
            if matches!(
                init_disposition(ramdisk_state),
                InitDisposition::MoveInitToReal
            ) && cpio.exists("init")
            {
                cpio.mv("init", "init.real")?;
            }

            match ramdisk_state {
                RamdiskState::Compatible => {
                    println!(
                        "- Official KernelSU image detected \
                         (compatible layout: `init` + `init.real`)"
                    );
                    println!(
                        "-   Direct install is ALLOWED; `init.real` (the real Android init) is left untouched"
                    );
                }
                RamdiskState::Foreign => {
                    println!(
                        "- Warning: this image was patched by an INCOMPATIBLE root solution \
                         (kernelsu.ko without init.real, or a foreign init.real)"
                    );
                    println!("- init.real is left untouched (never overwrite the real init)");
                    println!(
                        "- Our init + sevenk.ko will replace theirs; the real init is preserved"
                    );
                    // v2.6：走到这里只可能是**离线打补丁**（写文件，安全退路）
                    // 或用户显式 `--force-foreign` 强过写分区闸（危险，风险自负）。
                    println!(
                        "-   (offline patch to a FILE, or --force-foreign: writing the partition \
                         would have been refused)"
                    );
                }
                RamdiskState::Ours | RamdiskState::Stock => {}
            }

            // B6.1：打包模块前确认它和运行内核同源（非 Android 主机 / 读不到 /proc/version 时跳过）
            ensure_module_matches_running_kernel(kernelsu_ko.as_ref().as_ref())?;

            cpio.add("init", CpioEntry::regular(0o755, ksu_init))?;
            // 先克隆一份字节：同一次打补丁里要写两个条目，而 `CpioEntry::regular` 会接管所有权。
            let official_name_copy = kernelsu_ko.as_ref().as_ref().to_vec();
            cpio.add(OURS_KSU_ENTRY, CpioEntry::regular(0o755, kernelsu_ko))?;
            cpio.add(
                OFFICIAL_KSU_ENTRY,
                CpioEntry::regular(0o755, Box::new(official_name_copy)),
            )?;

            // B1：只要要刷分区（或者用户强制 --backup）就先备份，而且备份必须校验通过。
            #[cfg(target_os = "android")]
            if flash || backup {
                // B1：**只有当前镜像是原厂态才登记成 "stock" 备份**。
                // 显式 --backup 只决定“要不要做这份备份”，不决定“它算不算原厂”——
                // 旧写法 `backup || ramdisk_state == RamdiskState::Stock` 会在
                // “已经打过补丁的镜像”上把**已打补丁的镜像**写成对 `stock_image.sha1`，
                // 直接毁掉 restore 的退路（v2.2 的 🟠）。
                let record_stock = should_record_stock(ramdisk_state, backup);
                match do_backup(&mut cpio, &boot_image_file, record_stock) {
                    Ok(path) => flash_backup = Some(path),
                    Err(e) => {
                        println!("- Backup stock image failed: {e:?}");
                        // 没有一份可证明正确的备份就写分区 = 没有退路，绝不允许
                        if flash {
                            bail!("refusing to flash: the backup could not be verified ({e:?})");
                        }
                    }
                }
            }
        }

        // B1：走到刷写这一步之前，必须手里有备份（覆盖 --no-install 之类绕过备份的路径）
        #[cfg(target_os = "android")]
        ensure!(
            !flash || flash_backup.is_some(),
            "refusing to flash without a verified backup of the current boot image"
        );

        let mut ksu_config: Vec<String> = cpio
            .entry_by_name("ksu_config")
            .and_then(CpioEntry::data)
            .and_then(|v| str::from_utf8(v).ok())
            .map(|v| v.split(' ').map(std::borrow::ToOwned::to_owned).collect())
            .unwrap_or_default();

        let mut apply_config = |name: &str, value: &str, add: bool| {
            let has_value = ksu_config.iter().any(|v| v == value);

            if add {
                println!("- Adding {name} config");
                if !has_value {
                    ksu_config.push(value.to_owned());
                }
            } else if has_value {
                println!("- Removing {name} config");
                ksu_config.retain(|v| v != value);
            }
        };

        apply_config("no custom rc", "norc=1", no_custom_rc);
        apply_config("allow shell", "allow_shell=1", allow_shell);
        if let Some(bundled) = bundled_lkm {
            apply_config("bundled LKM", "bundled=1", bundled);
        }

        if ksu_config.is_empty() {
            cpio.rm("ksu_config", false);
        } else {
            let data = ksu_config.join(" ").into_bytes();
            cpio.add("ksu_config", CpioEntry::regular(0o644, Box::new(data)))?;
        }

        // remove legacy config file
        cpio.rm("allow_shell", false);

        if enable_adbd || adb_debug_prop.is_some() {
            println!("- Adding adb_debug props");
            cpio.add(
                "force_debuggable",
                CpioEntry::regular(0o644, Box::new(Vec::<u8>::new())),
            )?;

            let mut prop = Vec::<u8>::new();
            if enable_adbd {
                println!("- Adding props to enable adbd");
                prop.extend_from_slice(
                    b"ro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\n",
                );
            }
            if let Some(extra) = adb_debug_prop {
                println!("- Adding custom props");
                prop.extend_from_slice(extra.as_bytes());
            }
            cpio.add("adb_debug.prop", CpioEntry::regular(0o644, Box::new(prop)))?;
        } else {
            if cpio.exists("force_debuggable") {
                println!("- Removing /force_debuggable");
                cpio.rm("force_debuggable", false);
            }
            if cpio.exists("adb_debug.prop") {
                println!("- Removing /adb_debug.prop");
                cpio.rm("adb_debug.prop", false);
            }
        }

        let mut new_cpio = Vec::<u8>::new();
        cpio.dump(&mut new_cpio)?;

        if let Some(idx) = vendor_ramdisk_idx {
            patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
        } else {
            patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
        }

        println!("- Repacking boot image");
        let mut new_boot_buf = Cursor::new(Vec::<u8>::with_capacity(boot_image.get_size()));
        patcher.patch(&mut new_boot_buf)?;
        let new_boot_bytes = new_boot_buf.into_inner();

        // Free the source mmap so the boot partition is no longer mapped read-only,
        // otherwise some kernels reject the subsequent write.
        drop(boot_image);
        drop(boot_image_data);

        #[cfg(target_os = "android")]
        if flash {
            println!("- Flashing new boot image");
            let bootdevice = boot_image_file.display().to_string();
            // B3：把备份路径交给 flash_partition，写校验失败时它能立刻回滚
            flash_partition(&bootdevice, &new_boot_bytes, flash_backup.as_deref())?;
            if ota {
                post_ota()?;
            }
        }

        #[cfg(target_os = "android")]
        let should_write_output = patch_file || !flash || out_name.is_some() || out.is_some();
        #[cfg(not(target_os = "android"))]
        let should_write_output = true;

        if should_write_output {
            let output_dir = out.unwrap_or(std::env::current_dir()?);
            let name = out_name.unwrap_or_else(|| {
                let now = chrono::Utc::now();
                // 产物用我们自己的名字（2026-09-17 用户要求）：以前叫 kernelsu_patched_…，
                // 容易被当成上游 KernelSU 的产物。文件名只影响这一份输出，镜像里的内容不变。
                format!("7kimisupatched_{}.img", now.format("%Y%m%d_%H%M%S"))
            });
            let output_image = output_dir.join(name);
            std::fs::write(&output_image, &new_boot_bytes).context("write out new boot failed")?;
            println!("- Output file is written to");
            println!("- {}", output_image.display().to_string().trim_matches('"'));
        }

        println!("- Done!");
        Ok(())
    };

    let result = inner();
    if let Err(ref e) = result {
        println!("- Patch Error: {e}");
    }
    result
}

#[derive(clap::Args, Debug)]
pub struct BootRestoreArgs {
    /// boot image path, if not specified, will try to find the boot image automatically
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// Flash it to boot partition after restore
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = "false")]
    pub flash: bool,

    /// 【DANGEROUS】Restore even when the image was NOT patched by this 7kimisu
    /// (layout-incompatible / another root solution), or when the running kernel is
    /// not 7kimisu (advanced users only).
    ///
    /// v2.8：宿主机（非 Android）也认这个开关 —— 这样"`--force-foreign` 到底有没有接线"
    /// 才能被一条**走完整流程**的单测证明（v2.4 只测了纯判据，于是漏掉了它其实是死开关）。
    #[arg(long, default_value = "false")]
    pub force_foreign: bool,

    /// Output path. If not specified, will use current directory.
    /// If specified, the boot image will be written to the directory
    /// even if --flash is specified.
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// Output path. If not specified, will use current directory.
    #[cfg(not(target_os = "android"))]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// File name of the output. If specified, the boot image will be
    /// written to the output directory even if --flash is specified.
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// File name of the output.
    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,
}

pub fn restore(args: BootRestoreArgs) -> Result<()> {
    let BootRestoreArgs {
        boot: image,
        out_name,
        out,
        #[cfg(target_os = "android")]
        flash,
        force_foreign,
    } = args;

    // v2.8：宿主机（非 Android）也照 `--force-foreign` 走 —— 准入判据与 Android 侧**完全同源**。
    // 旧写法在宿主上钉死 `force_foreign = false`，于是"这条开关有没有接线"在单测里
    // 永远验证不到（v2.4 的漏点）。

    // 🔴1（v2.4）/ v2.6：restore 也会写分区（--flash），同样打印一遍内核归属**信息**。
    // 但**不再按内核归属拒绝** —— 拒绝与否由下面的 `must_refuse_foreign_restore`
    // 按**镜像状态**判（`Foreign` 才拒，`Ours`/`Compatible` 放行）。
    #[cfg(target_os = "android")]
    report_kernel_owner(flash);

    #[cfg(target_os = "android")]
    let kmi = get_current_kmi().unwrap_or_default();

    #[cfg(target_os = "android")]
    let image_supplied = image.is_some();

    let boot_image_file = if let Some(image) = image {
        ensure!(image.exists(), "boot image not found");
        std::fs::canonicalize(image)?
    } else {
        #[cfg(target_os = "android")]
        {
            auto_boot_partition_path(&kmi, false, false, &None)
        }
        #[cfg(not(target_os = "android"))]
        {
            bail!("Please specify a boot image");
        }
    };

    #[cfg(target_os = "android")]
    println!("- Bootdevice: {}", boot_image_file.display());

    println!("- Unpacking boot image");
    let bootimage_data = map_file(&boot_image_file)?;
    let boot_image = BootImage::parse(&bootimage_data)?;
    enforce_bootimage_version(&boot_image)?;

    let (mut cpio, vendor_ramdisk_idx) =
        if let Some(ramdisk_image) = boot_image.get_blocks().get_ramdisk() {
            extract_ramdisk(ramdisk_image)?
        } else {
            bail!("No compatible ramdisk found.")
        };

    // 🟠2（v2.4）/ v2.5：认亲判据与 `RamdiskState::of()` **完全统一**。
    //   v2.5 起**放宽**到 `Ours` 或 `Compatible` 都允许还原（用户 2026-09-21 要求）：
    //   官方 KernelSU 的镜像布局与我们同源（`init` + `init.real`），拆掉模块、把真 init 放回
    //   `init` 得到的就是一张干净能开机的原厂镜像 —— 这不是"拆别人的 root 然后砖"，是安全还原。
    //   仍然拒的只有 `Foreign`（布局不兼容：无 `init.real` 的 `kernelsu.ko` / 别的方案）。
    //
    // 🟠1（v2.8 修 v2.4 审计遗留）：这里以前是两句**各管各的**判据 —— 第一句看 `force_foreign`，
    //   紧跟着的 `ensure!(is_restorable(state))` **完全不看开关**，于是 `--force-foreign`
    //   是个**死开关**：Foreign 镜像加了开关照样被第二句拦下（`Stock` 同理）。
    //   判据没错，错的是"接线"。现在收进 [restore_admission] 一处，
    //   `restore()` 与 `rebuild_without_ksu()` 共用，不可能再出现"改了一处、另一处还卡着"。
    //   v2.4 只测了纯判据（`is_restorable` / `must_refuse_foreign_restore` 各自断言），
    //   所以漏了 —— v2.8 补的单测**从 `restore()` 入口进**，见 `force_foreign_restore_full_flow`。
    let ramdisk_state = RamdiskState::of(&cpio);
    restore_admission(ramdisk_state, force_foreign)?;
    if ramdisk_state == RamdiskState::Compatible {
        println!(
            "- This image was patched by official KernelSU (compatible layout); \
             restoring it to stock (keeping the real init at `init.real`)"
        );
    }

    #[cfg(target_os = "android")]
    let mut stock_boot: Option<PathBuf> = None;

    #[cfg(target_os = "android")]
    if let Some(backup_file) = cpio.entry_by_name(BACKUP_FILENAME) {
        let sha = String::from_utf8(backup_file.data().unwrap_or_default().to_vec())?;
        let sha = sha.trim();
        let backup_path =
            PathBuf::from(KSU_BACKUP_DIR).join(format!("{KSU_BACKUP_FILE_PREFIX}{sha}"));
        if backup_path.is_file() {
            println!("- Using backup file {}", backup_path.display());
            stock_boot = Some(backup_path);
        } else {
            println!("- Warning: no backup {} found!", backup_path.display());
        }
        if let Err(e) = clean_backup(sha) {
            println!("- Warning: Cleanup backup image failed: {e}");
        }
    } else {
        println!("- Backup info is absent!");
    }

    #[cfg(target_os = "android")]
    let mut stock_source: Option<PathBuf> = None;

    let new_boot_bytes: Vec<u8> = {
        #[cfg(target_os = "android")]
        {
            if let Some(stock_path) = stock_boot {
                let bytes = std::fs::read(&stock_path)
                    .with_context(|| format!("read stock boot {}", stock_path.display()))?;
                stock_source = Some(stock_path);
                bytes
            } else {
                rebuild_without_ksu(&boot_image, &mut cpio, vendor_ramdisk_idx, force_foreign)?
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            rebuild_without_ksu(&boot_image, &mut cpio, vendor_ramdisk_idx, force_foreign)?
        }
    };

    drop(boot_image);
    drop(bootimage_data);

    #[cfg(target_os = "android")]
    if flash {
        if let Some(ref source) = stock_source {
            println!("- Flashing new boot image from {}", source.display());
        } else {
            println!("- Flashing new boot image");
        }
        let bootdevice = boot_image_file.display().to_string();
        // B3：先给“刷之前的当前分区”留一份可校验的备份，写坏了能立刻回滚。
        // record_stock = false —— 这里只是给自己留退路，不能动 ramdisk 里的 stock 标记。
        let rollback = match do_backup(&mut cpio, &boot_image_file, false) {
            Ok(path) => Some(path),
            Err(e) => {
                // 以前这里只 print 一行 warning 就继续刷 —— 等于“没有退路还往下写”。
                // restore 是用户自己选的恢复动作（此刻分区上的镜像可能就是坏的），
                // 所以不在这里直接拒绝；但必须把后果**明确**摆出来，绝不静默。
                println!("- WARNING: cannot back up the current partition before restore: {e:?}");
                println!("- WARNING: there will be NO automatic rollback if this flash fails.");
                println!(
                    "- WARNING: if the flash fails, DO NOT REBOOT; \
                     flash a stock boot/init_boot image from a computer with fastboot."
                );
                None
            }
        };
        flash_partition(&bootdevice, &new_boot_bytes, rollback.as_deref())?;
    }

    #[cfg(target_os = "android")]
    let should_write_output = image_supplied || !flash || out_name.is_some() || out.is_some();
    #[cfg(not(target_os = "android"))]
    let should_write_output = true;

    if should_write_output {
        let output_dir = out.unwrap_or(std::env::current_dir()?);
        let name = out_name.unwrap_or_else(|| {
            let now = chrono::Utc::now();
            format!("kernelsu_restore_{}.img", now.format("%Y%m%d_%H%M%S"))
        });
        let output_image = output_dir.join(name);
        std::fs::write(&output_image, &new_boot_bytes).context("copy out new boot failed")?;
        println!("- Output file is written to");
        println!("- {}", output_image.display().to_string().trim_matches('"'));
    }

    println!("- Done!");
    Ok(())
}

fn rebuild_without_ksu(
    boot_image: &BootImage<'_>,
    cpio: &mut Cpio,
    vendor_ramdisk_idx: Option<usize>,
    force_foreign: bool,
) -> Result<Vec<u8>> {
    // 🟠2（v2.4）/ v2.5：纵深防御 —— 这个函数会**删模块 + 把 init.real 改回 init**，
    // 所以它自己也必须确认"这张镜像可以安全还原"（Ours 或官方 KSU 的 Compatible 布局）。
    // `restore()` 已经在入口判过一次，这里再判一次是为了防止将来有新的调用方忘了判
    //（判据与 `RamdiskState::of` 同源）。
    //
    // ⚠️ v2.8：这道纵深防御**必须和入口那道看同一个开关** —— 旧写法把 `force_foreign` 排除在外，
    //    于是"入口放行了、这里又被拦下"，`--force-foreign` 走不到底（v2.4 死开关的第二半）。
    //    现在显式 `--force-foreign` 时一并放行；**不加开关时一刀不放**（fail-closed，与之前一致）。
    if !force_foreign {
        ensure!(
            is_restorable(RamdiskState::of(cpio)),
            "refusing to strip an image that is neither ours nor an official-KernelSU \
             (compatible) image (pass `--force-foreign` to force)"
        );
    }

    println!("- Removing KernelSU from boot image");
    cpio.rm(OURS_KSU_ENTRY, false);
    // v2.5：官方名那条也要清掉 ——
    //   · 我们放的副本：不清掉官方 ksud 还会以为“已打过补丁”，还原后再“直接安装”就会踩坑；
    //   · 官方 KSU 留下的真模块：这一条正是"把官方 root 拆掉、还原成原厂镜像"的本意。
    //     顺便也清掉改名前的旧条目名，避免老机器上还原后留下孤儿模块文件。
    cpio.rm(OFFICIAL_KSU_ENTRY, false);
    if cpio.exists("init.real") {
        cpio.mv("init.real", "init")?;
    }

    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio)?;

    println!("- Repacking boot image");
    let mut patcher = BootImagePatchOption::new(boot_image);
    if let Some(idx) = vendor_ramdisk_idx {
        patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }

    let mut buf = Cursor::new(Vec::<u8>::with_capacity(boot_image.get_size()));
    patcher.patch(&mut buf)?;
    Ok(buf.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cpio_with(names: &[&str]) -> Cpio {
        let mut cpio = Cpio::new();
        for name in names {
            cpio.add(name, CpioEntry::regular(0o755, Box::new(Vec::<u8>::new())))
                .unwrap();
        }
        cpio
    }

    /// B6 规则：**只有主.次（KMI）参与比较** —— sublevel 与尾部组件一律忽略。
    ///
    /// 这条正是 2026-09-21 实测修正的核心：内置的 `android16-6.12_sevenk.ko` 是 6.12.76，
    /// 而目标设备的运行内核是 6.12.30 —— 若要求 x.y.z 全等，谁都装不了内核。
    #[test]
    fn kernel_version_prefix_only_takes_major_minor() {
        assert_eq!(
            kernel_version_prefix(
                "5.15.149-android13-8-g1234abcd SMP preempt mod_unload modversions aarch64"
            )
            .as_deref(),
            Some("5.15")
        );
        assert_eq!(kernel_version_prefix("6.6.30").as_deref(), Some("6.6"));
        // 同一主.次但 sublevel / 尾部不同 -> 前缀相同（这正是允许的那部分差异）
        assert_eq!(
            kernel_version_prefix("6.12.30-android16-8 SMP preempt aarch64").as_deref(),
            Some("6.12")
        );
        assert_eq!(
            kernel_version_prefix("6.12.76-android16-11-gdeadbeef SMP preempt aarch64").as_deref(),
            Some("6.12")
        );
        // 跨 KMI 必须区分开（5.10 vs 6.6 不能混）
        assert_ne!(
            kernel_version_prefix("5.10.209-android12-9 SMP preempt aarch64"),
            kernel_version_prefix("6.6.30-android15-8 SMP preempt aarch64")
        );
        // 解析不出来一律返回 None（调用方按“拒绝”处理）
        assert_eq!(kernel_version_prefix("SMP preempt mod_unload"), None);
        assert_eq!(kernel_version_prefix("5"), None);
        assert_eq!(kernel_version_prefix(""), None);
    }

    #[test]
    fn proc_version_prefix_handles_proc_version_line() {
        assert_eq!(
            proc_version_prefix(
                "Linux version 6.1.75-android14-11-gabc123 (builder@host) #1 SMP PREEMPT"
            )
            .as_deref(),
            Some("6.1")
        );
    }

    #[test]
    fn find_module_vermagic_reads_modinfo_entry() {
        let mut ko = Vec::new();
        ko.extend_from_slice(b"name=sevenk\0");
        ko.extend_from_slice(
            b"vermagic=5.15.149-android13-8-g1234 SMP preempt mod_unload modversions aarch64\0",
        );
        ko.extend_from_slice(b"depends=\0");
        assert_eq!(
            find_module_vermagic(&ko).as_deref(),
            Some("5.15.149-android13-8-g1234 SMP preempt mod_unload modversions aarch64")
        );

        // 没有 vermagic 条目 -> None（调用方会拒绝继续）
        assert_eq!(find_module_vermagic(b"name=sevenk\0depends=\0"), None);
    }

    /// B4（v2.5 四态）：必须能区分原厂 / 我们打的 / 官方 KSU（兼容）/ 别人的（不兼容）。
    #[test]
    fn ramdisk_state_classifies_four_ways() {
        assert_eq!(RamdiskState::of(&Cpio::new()), RamdiskState::Stock);
        assert_eq!(RamdiskState::of(&cpio_with(&["init"])), RamdiskState::Stock);
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real"])),
            RamdiskState::Foreign
        );
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real", "sevenk.ko"])),
            RamdiskState::Ours
        );
        // 只有我们的 `sevenk.ko` 才算 Ours
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "sevenk.ko"])),
            RamdiskState::Ours
        );
        // v2.5：双条目布局（`sevenk.ko` + 官方名的 `kernelsu.ko`）同样是我们
        assert_eq!(
            RamdiskState::of(&cpio_with(&[
                "init",
                "init.real",
                "sevenk.ko",
                "kernelsu.ko"
            ])),
            RamdiskState::Ours
        );
        // 🔴 v2.3（审计 B）→ v2.5 细化：`kernelsu.ko` **不是我们**；
        //    但它是不是"官方 KernelSU"要看**布局**：
        //      · `kernelsu.ko` + `init.real`（官方 KSU / 我们 ≤v0.13.151 老包）→ Compatible（可接管）
        //      · `kernelsu.ko` 但没有 `init.real`（畸形/未知）→ Foreign（叠了必砖）
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real", "kernelsu.ko"])),
            RamdiskState::Compatible
        );
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "kernelsu.ko"])),
            RamdiskState::Foreign
        );
    }

    /// B1（v2.3 修掉的 🟠）：**显式 `--backup` 不得再污染 `stock_image.sha1`**。
    ///
    /// 旧写法是 `backup || ramdisk_state == RamdiskState::Stock`：在“已经打过补丁的镜像”上
    /// 勾了强制备份，就会把**已打补丁的镜像**登记成原厂备份 → `restore` 的退路被毁。
    /// 现在只认 `ramdisk_state == Stock`；`explicit_backup` 只影响“要不要做这份备份”。
    #[test]
    fn stock_backup_is_recorded_only_for_stock_images() {
        // 原厂态：登记（普通安装 / 显式备份结果一样）
        assert!(should_record_stock(RamdiskState::Stock, false));
        assert!(should_record_stock(RamdiskState::Stock, true));
        // 我们自己打过补丁的镜像：**显式 --backup 也不登记**
        // （旧写法在这里返回 true —— 这正是被审计抓到的那个 🟠）
        assert!(!should_record_stock(RamdiskState::Ours, false));
        assert!(!should_record_stock(RamdiskState::Ours, true));
        // 别的 root 方案改过的镜像：同样不登记
        assert!(!should_record_stock(RamdiskState::Foreign, false));
        assert!(!should_record_stock(RamdiskState::Foreign, true));
        // v2.5：官方 KernelSU 的镜像（Compatible）**也不是原厂** —— 备份照做但不登记成原厂，
        // 否则 `stock_image.sha1` 会指向一张还被 KSU 改过的镜像，`restore` 的退路就毁了。
        assert!(!should_record_stock(RamdiskState::Compatible, false));
        assert!(!should_record_stock(RamdiskState::Compatible, true));
    }

    /// B2 + B3：长度判据 + “刷写失败该报哪一种结局”的判据。
    ///
    /// `flash_partition` 本体要 ioctl 真块设备（宿主机跑不了），所以把两道判据抽成纯函数测。
    #[test]
    fn flash_guards_reject_oversized_image_and_unrecoverable_failure() {
        // B2：顶满分区是允许的（有些机器 boot 镜像就正好等于分区大小）；多一个字节就拒绝
        assert!(image_fits_partition(4096, 4096));
        assert!(image_fits_partition(1024, 4096));
        assert!(!image_fits_partition(4097, 4096));
        assert!(!image_fits_partition(1, 0));

        // B3：只有“有备份 **且** 回滚写回 + 再验 sha1 都成功”才允许说“设备应仍能开机”
        assert_eq!(
            flash_failure_action(true, true),
            FlashFailureAction::RolledBack
        );
        // 有备份但回滚失败（或回滚后 sha1 对不上）-> 必须报“请勿重启”
        assert_eq!(
            flash_failure_action(true, false),
            FlashFailureAction::PartitionMayBeCorrupted
        );
        // 没有备份 -> 必须报“请勿重启”（旧版会在 write_all/sync_all/read_exact 失败时静默返回）
        assert_eq!(
            flash_failure_action(false, false),
            FlashFailureAction::PartitionMayBeCorrupted
        );
        // 不可能出现的组合也不许当成“安全”
        assert_eq!(
            flash_failure_action(false, true),
            FlashFailureAction::PartitionMayBeCorrupted
        );
    }

    /// B5：没有 ramdisk 的镜像只能打补丁到文件，`--flash` 必须被拒。
    #[test]
    fn no_ramdisk_image_must_not_be_flashed() {
        assert!(must_refuse_no_ramdisk_flash(true, false));
        // 打补丁到文件（--flash 没给）仍然允许
        assert!(!must_refuse_no_ramdisk_flash(false, false));
        // 有 ramdisk 就正常放行
        assert!(!must_refuse_no_ramdisk_flash(true, true));
    }

    /// 🔴1（v2.4）→ **v2.6 换判据**（用户 2026-09-21 明确要求）：
    ///
    /// 闸门判的是**目标镜像**（能不能安全打），**不是运行中的内核**。
    ///
    /// 旧判据（v2.4/v2.5）把"内核不是我们的 + 写分区"一律拒 —— 于是设备上跑着
    /// **官方 KernelSU** 时，用户在 App 里点"直接安装"会被自己的 ksud 拒掉。
    /// 而官方 KSU 的镜像布局（`init` + `init.real` + `kernelsu.ko`）与我们**同源兼容**：
    /// 真 init 安全地待在 `init.real`，我们只换 `init` + 换模块条目，一个字节都不碰它
    /// （见 `compatible_state_must_never_touch_init_real` 与 `init_disposition`）。
    ///
    /// ```text
    /// 目标镜像状态            写分区（--flash）
    /// Ours                    ✅ 允许
    /// Compatible（官方 KSU）   ✅ 允许   ← 本条就是用户要打通的那条路
    /// Stock（原厂）            ✅ 允许
    /// Foreign（布局不兼容）     ❌ 拒（--force-foreign 才放行）
    /// ```
    #[test]
    fn v26_write_guard_judges_the_image_not_the_kernel() {
        let ours = RamdiskState::of(&cpio_with(&["init", "init.real", "sevenk.ko"]));
        let compatible = RamdiskState::of(&cpio_with(&["init", "init.real", "kernelsu.ko"]));
        let stock = RamdiskState::of(&cpio_with(&["init"]));
        let malformed = RamdiskState::of(&cpio_with(&["init", "kernelsu.ko"]));
        let other = RamdiskState::of(&cpio_with(&["init", "init.real"]));
        assert_eq!(ours, RamdiskState::Ours);
        assert_eq!(compatible, RamdiskState::Compatible);
        assert_eq!(stock, RamdiskState::Stock);
        assert_eq!(malformed, RamdiskState::Foreign);
        assert_eq!(other, RamdiskState::Foreign);

        // ✅ 放行的三种 —— 特别注意 `Compatible`：**内核是官方 KernelSU 的**（不是我们的）
        //    也必须放行，这正是 v2.6 与 v2.4/v2.5 的分水岭。
        for state in [ours, compatible, stock] {
            assert!(
                !must_refuse_unsafe_flash(state, true, false),
                "{state:?} 必须允许写分区（判据是镜像，不是内核）"
            );
        }

        // ❌ 布局不兼容的两类（Foreign）→ 拒
        for state in [malformed, other] {
            assert!(
                must_refuse_unsafe_flash(state, true, false),
                "{state:?} 布局不兼容，必须拒写分区"
            );
        }

        // 离线打补丁到文件（不写分区）**永远放行** —— 安全退路
        for state in [ours, compatible, stock, malformed, other] {
            assert!(!must_refuse_unsafe_flash(state, false, false));
        }

        // `--force-foreign` 仍然是唯一的逃生口（保留，但正常情况下不再需要）
        for state in [malformed, other] {
            assert!(!must_refuse_unsafe_flash(state, true, true));
        }
    }

    /// v2.6：查询命令的**输出词**（App 解析它，机器接口，必须稳定）。
    #[test]
    fn ramdisk_state_wire_words_are_stable() {
        assert_eq!(RamdiskState::Ours.as_str(), "ours");
        assert_eq!(RamdiskState::Compatible.as_str(), "compatible");
        assert_eq!(RamdiskState::Foreign.as_str(), "foreign");
        assert_eq!(RamdiskState::Stock.as_str(), "stock");
    }

    /// v2.6：`query_ramdisk_state()` 走**真镜像**的输出 —— 这就是 App 按钮门控拿到的值。
    ///
    /// 用项目 2026-09-12 的产物 `dist8/init_boot_a_7k_v08.img`：ramdisk = `init` + 真模块
    /// `kernelsu.ko` + `init.real`，**正是官方 KernelSU 打完补丁的形状** →
    /// 必须报 `compatible`（= App 那边会放行"直接安装"）。
    ///
    /// 镜像不在时**跳过**（不算失败）；读不到/畸形时必须 `unknown`（fail-closed）。
    #[test]
    fn query_ramdisk_state_on_official_like_image() {
        let home = std::env::var("HOME").unwrap_or_default();
        let src = PathBuf::from(format!(
            "{home}/projects/7kkernel/dist8/init_boot_a_7k_v08.img"
        ));
        if !src.is_file() {
            println!("- skipped: {} not found", src.display());
            return;
        }
        assert_eq!(
            query_ramdisk_state(Some(src.as_path())),
            "compatible",
            "官方 KSU 形状的镜像必须报 compatible（否则 App 不会放行直接安装）"
        );
        // 失败一律 `unknown`（App 按 fail-closed 处理：不显示"直接安装"）
        assert_eq!(
            query_ramdisk_state(Some(Path::new("/nonexistent/definitely-not-an-image.img"))),
            "unknown"
        );
        assert_eq!(query_ramdisk_state(Some(src.as_path())), "compatible"); // 只读，可重复查
    }

    /// v2.5 定稿：**四态判据表**（用户 2026-09-21 明确要求的那张表，逐行钉死）。
    ///
    /// | 镜像里有什么 | 判定 | 写分区（`--flash`） |
    /// |---|---|---|
    /// | 有 `sevenk.ko` | Ours | ✅ 允许 |
    /// | 有 `kernelsu.ko` **且** `init.real` | **Compatible**（官方 KSU / ≤v0.13.151 老包）| ✅ **允许直接安装** |
    /// | 有 `kernelsu.ko` **但无** `init.real` | Foreign（畸形/未知）| ❌ 拒 |
    /// | 只有 `init.real`、无 KSU 模块 | Foreign（APatch / 别的 fork）| ❌ 拒 |
    /// | 都没有 | Stock（原厂）| ✅ 允许 |
    #[test]
    fn v25_ramdisk_state_table() {
        // ① sevenk.ko → Ours
        let ours = RamdiskState::of(&cpio_with(&["init", "init.real", "sevenk.ko"]));
        assert_eq!(ours, RamdiskState::Ours);
        assert!(!must_refuse_unsafe_flash(ours, true, false));
        assert!(is_restorable(ours));

        // ①' v2.5 双条目（sevenk.ko + kernelsu.ko）→ 仍然 Ours
        let ours_dual = RamdiskState::of(&cpio_with(&[
            "init",
            "init.real",
            "sevenk.ko",
            "kernelsu.ko",
        ]));
        assert_eq!(ours_dual, RamdiskState::Ours);
        assert!(!must_refuse_unsafe_flash(ours_dual, true, false));

        // ② kernelsu.ko + init.real → Compatible，**必须允许直接安装**
        let compatible = RamdiskState::of(&cpio_with(&["init", "init.real", "kernelsu.ko"]));
        assert_eq!(compatible, RamdiskState::Compatible);
        assert!(
            !must_refuse_unsafe_flash(compatible, true, false),
            "官方 KernelSU 的镜像必须允许 --flash（直接安装）"
        );
        assert!(is_restorable(compatible), "Compatible 也允许 restore");

        // ③ kernelsu.ko 但没有 init.real → Foreign，拒写分区
        let malformed = RamdiskState::of(&cpio_with(&["init", "kernelsu.ko"]));
        assert_eq!(malformed, RamdiskState::Foreign);
        assert!(must_refuse_unsafe_flash(malformed, true, false));
        assert!(!must_refuse_unsafe_flash(malformed, false, false)); // 离线打文件仍允许
        assert!(must_refuse_foreign_restore(malformed, false));

        // ④ 只有 init.real → Foreign，拒
        let other = RamdiskState::of(&cpio_with(&["init", "init.real"]));
        assert_eq!(other, RamdiskState::Foreign);
        assert!(must_refuse_unsafe_flash(other, true, false));
        assert!(must_refuse_foreign_restore(other, false));
        assert!(!is_restorable(other));

        // ⑤ 都没有 → Stock，允许
        let stock = RamdiskState::of(&cpio_with(&["init"]));
        assert_eq!(stock, RamdiskState::Stock);
        assert!(!must_refuse_unsafe_flash(stock, true, false));
        assert!(
            !is_restorable(stock),
            "原厂镜像不叫\"可还原的补丁\"，应报没打过补丁"
        );
        assert!(!must_refuse_foreign_restore(stock, false));
    }

    /// v2.5 安全红线（用户特别点名）：**Compatible 分支绝不能覆盖 `init.real`**。
    ///
    /// `init.real` 里是**真正的 Android init**；上一次变砖就是拿我们的 `ksuinit` 覆盖了它，
    /// 于是开机后 `ksuinit` 反复 exec 自己 → 无限重启。这里把"只有 Stock 才允许挪 init"
    /// 这条判据钉死，并用一张"官方 KSU 镜像"的 cpio 验证：走完处置后真 init 逐字节没变。
    #[test]
    fn compatible_state_must_never_touch_init_real() {
        const REAL_INIT: &[u8] = b"REAL_ANDROID_INIT";
        const OUR_KSUINIT: &[u8] = b"OUR_KSUINIT";

        // 判据层：只有 Stock 允许 mv init -> init.real
        assert_eq!(
            init_disposition(RamdiskState::Stock),
            InitDisposition::MoveInitToReal
        );
        for state in [
            RamdiskState::Ours,
            RamdiskState::Compatible,
            RamdiskState::Foreign,
        ] {
            assert_eq!(
                init_disposition(state),
                InitDisposition::KeepInitReal,
                "{state:?} 绝不能挪 init（挪了就会覆盖 init.real 里的真 init）"
            );
        }

        // 行为层：照 `patch()` 的顺序对一张"官方 KSU 镜像"做一遍，真 init 必须原封不动
        let mut cpio = Cpio::new();
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(b"OFFICIAL_KSUINIT".to_vec())),
        )
        .unwrap();
        cpio.add(
            "init.real",
            CpioEntry::regular(0o755, Box::new(REAL_INIT.to_vec())),
        )
        .unwrap();
        cpio.add(
            OFFICIAL_KSU_ENTRY,
            CpioEntry::regular(0o755, Box::new(b"OFFICIAL_MODULE".to_vec())),
        )
        .unwrap();

        let state = RamdiskState::of(&cpio);
        assert_eq!(state, RamdiskState::Compatible);
        cpio.rm(OFFICIAL_KSU_ENTRY, false);
        if matches!(init_disposition(state), InitDisposition::MoveInitToReal) && cpio.exists("init")
        {
            cpio.mv("init", "init.real").unwrap();
        }
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(OUR_KSUINIT.to_vec())),
        )
        .unwrap();
        cpio.add(
            OURS_KSU_ENTRY,
            CpioEntry::regular(0o755, Box::new(b"OUR_MODULE".to_vec())),
        )
        .unwrap();
        cpio.add(
            OFFICIAL_KSU_ENTRY,
            CpioEntry::regular(0o755, Box::new(b"OUR_MODULE".to_vec())),
        )
        .unwrap();

        assert_eq!(
            cpio.entry_by_name("init.real").unwrap().data().unwrap(),
            REAL_INIT,
            "Compatible 分支把真 init 覆盖了！"
        );
        assert_eq!(
            cpio.entry_by_name("init").unwrap().data().unwrap(),
            OUR_KSUINIT
        );
        assert!(cpio.exists(OURS_KSU_ENTRY) && cpio.exists(OFFICIAL_KSU_ENTRY));
        // 打完还是"我们"的镜像（v2.5 双条目），restore 认得出
        assert_eq!(RamdiskState::of(&cpio), RamdiskState::Ours);
    }

    /// 🟠2（v2.4）/ v2.5：**只有布局不兼容的别人镜像才拒 restore / 拒写分区**。
    #[test]
    fn incompatible_layout_is_refused_but_compatible_is_allowed() {
        // 畸形：有 kernelsu.ko 却没有 init.real —— 这种叠一层必砖，必须拒
        let malformed = RamdiskState::of(&cpio_with(&["init", "kernelsu.ko"]));
        assert_eq!(malformed, RamdiskState::Foreign);
        assert!(!is_restorable(malformed));
        assert!(must_refuse_foreign_restore(malformed, false));
        // 只有显式 --force-foreign 才放行
        assert!(!must_refuse_foreign_restore(malformed, true));

        // 别的 root 方案（只留 init.real）→ 同样拒绝
        let other = RamdiskState::of(&cpio_with(&["init", "init.real"]));
        assert_eq!(other, RamdiskState::Foreign);
        assert!(!is_restorable(other));

        // 我们自己打的补丁（sevenk.ko）→ 允许 restore
        for names in [
            vec!["init", "sevenk.ko"],
            vec!["init", "init.real", "sevenk.ko"],
        ] {
            let state = RamdiskState::of(&cpio_with(&names));
            assert_eq!(state, RamdiskState::Ours, "names={names:?}");
            assert!(is_restorable(state));
            assert!(!must_refuse_foreign_restore(state, false));
        }

        // v2.5：官方 KernelSU 的镜像（Compatible）→ **允许 restore**（布局同源，还原后能开机）
        let official = RamdiskState::of(&cpio_with(&["init", "init.real", "kernelsu.ko"]));
        assert_eq!(official, RamdiskState::Compatible);
        assert!(is_restorable(official));
        assert!(!must_refuse_foreign_restore(official, false));

        // 原厂镜像：既不是"别人的"，也不是"我们的" —— 报"没打过补丁"，不当成 Foreign 处理
        let stock = RamdiskState::of(&cpio_with(&["init"]));
        assert_eq!(stock, RamdiskState::Stock);
        assert!(!is_restorable(stock));
        assert!(!must_refuse_foreign_restore(stock, false));
    }

    /// v2.5：**双向兼容的双条目判据**。
    ///
    /// 判据一句话：**只有 `sevenk.ko` 是我们的笔迹**；`kernelsu.ko` 用来区分
    /// “官方 KSU（Compatible，可接管）”和“畸形/别的方案（Foreign，拒绝）”。
    #[test]
    fn dual_marker_layout_classification() {
        // ① 只有 `sevenk.ko`（v2.4 及更早我们打的镜像）→ Ours
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real", "sevenk.ko"])),
            RamdiskState::Ours
        );
        // ② 只有 `kernelsu.ko` + `init.real`（官方 KernelSU）→ Compatible
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real", "kernelsu.ko"])),
            RamdiskState::Compatible
        );
        // ③ 两个都有（v2.5 打出来的镜像）→ **Ours**（官方名不改变归属）
        assert_eq!(
            RamdiskState::of(&cpio_with(&[
                "init",
                "init.real",
                "sevenk.ko",
                "kernelsu.ko"
            ])),
            RamdiskState::Ours
        );
        // ④ 两个都没有 → Stock（原厂）
        assert_eq!(RamdiskState::of(&cpio_with(&["init"])), RamdiskState::Stock);
        assert_eq!(RamdiskState::of(&Cpio::new()), RamdiskState::Stock);
        // 附加（v2.3 就定的）：只有 `init.real`（别的 root 方案）必须 Foreign，不能当原厂
        assert_eq!(
            RamdiskState::of(&cpio_with(&["init", "init.real"])),
            RamdiskState::Foreign
        );

        // 4 种组合在 `restore` 侧的下场（判据同源）
        assert!(is_restorable(RamdiskState::Ours));
        assert!(is_restorable(RamdiskState::Compatible));
        assert!(!is_restorable(RamdiskState::Foreign));
        assert!(!is_restorable(RamdiskState::Stock));
        assert!(must_refuse_foreign_restore(RamdiskState::Foreign, false));
        assert!(!must_refuse_foreign_restore(
            RamdiskState::Compatible,
            false
        ));
        assert!(!must_refuse_foreign_restore(RamdiskState::Ours, false));
        assert!(!must_refuse_foreign_restore(RamdiskState::Stock, false));
    }

    /// 🟠1（v2.8）：`restore --force-foreign` 的**走完整流程**单测。
    ///
    /// 【为什么非要有它】v2.4 只测了**纯判据**（`is_restorable` / `must_refuse_foreign_restore`
    /// 各自断言），于是"判据全绿、`restore()` 里的接线却是错的"被漏掉了：
    /// 入口那句无条件的 `ensure!(is_restorable(state))` **根本不看** `--force-foreign`，
    /// 于是 Foreign / Stock 镜像加了开关照样被拒 = **死开关**（`rebuild_without_ksu` 里
    /// 那道纵深防御同样不看开关，是同一毛病的第二半）。
    ///
    /// 所以这条测试**从 `restore()`（真入口）进**，并且**自己造一张镜像**：
    /// 一张布局不兼容的"别人的" boot 镜像（有 `kernelsu.ko`、**没有** `init.real` = `Foreign`）。
    ///   · `force_foreign = false` → 必须**拒**，而且**一个字节都不许写出**；
    ///   · `force_foreign = true`  → 必须**放行到底**：产物真的生成，
    ///     且里面的 KSU 模块条目真的被拆掉（`kernelsu.ko` 没了、`init` 还在）。
    ///
    /// "判据"与"接线"一起证明：少任何一半，v2.4 那种漏法都可能重演。
    #[test]
    #[cfg(not(target_os = "android"))]
    fn force_foreign_restore_full_flow() {
        const FOREIGN_MODULE: &[u8] = b"ANOTHER_ROOT_SOLUTION_MODULE";

        // ── ① 造一张 Foreign 布局的 ramdisk（有 `kernelsu.ko`、没有 `init.real`）──
        let mut cpio = Cpio::new();
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(b"REAL_INIT".to_vec())),
        )
        .unwrap();
        cpio.add(
            OFFICIAL_KSU_ENTRY,
            CpioEntry::regular(0o755, Box::new(FOREIGN_MODULE.to_vec())),
        )
        .unwrap();
        assert_eq!(
            RamdiskState::of(&cpio),
            RamdiskState::Foreign,
            "测试素材本身必须是不兼容布局（Foreign），否则测不到这个开关"
        );
        let mut raw_cpio = Vec::<u8>::new();
        cpio.dump(&mut raw_cpio).unwrap();

        let dir = std::env::temp_dir().join(format!("sevenk-restore-flow-{}", std::process::id()));
        let out_dir = dir.join("out");
        std::fs::create_dir_all(&out_dir).unwrap();
        let src = dir.join("foreign.img");
        std::fs::write(&src, synthetic_boot_image(&raw_cpio)).unwrap();
        let name = "restored.img";

        // ── ② 不带 `--force-foreign`：必须拒，且不产出任何文件 ──────────────
        let denied = restore(BootRestoreArgs {
            boot: Some(src.clone()),
            force_foreign: false,
            out: Some(out_dir.clone()),
            out_name: Some(name.to_owned()),
        });
        let err = denied.expect_err("Foreign 镜像不带 --force-foreign 必须被拒");
        let msg = format!("{err:#}");
        assert!(
            msg.contains("refusing to restore") || msg.contains("not patched by 7kimisu"),
            "拒绝理由不对（应该是布局/归属那两条之一）：{msg}"
        );
        assert!(
            !out_dir.join(name).exists(),
            "被拒的 restore 居然写出了产物文件 —— 准入闸必须挡在写盘之前"
        );

        // ── ③ 带 `--force-foreign`：必须**走到底**（这就是死开关的验收点）──────
        restore(BootRestoreArgs {
            boot: Some(src),
            force_foreign: true,
            out: Some(out_dir.clone()),
            out_name: Some(name.to_owned()),
        })
        .expect("显式 --force-foreign 必须能走完整个 restore 流程（入口 + 纵深防御两道都要放行）");

        let produced = out_dir.join(name);
        assert!(produced.is_file(), "restore 说成功却没写出产物");
        let produced_bytes = std::fs::read(&produced).unwrap();
        let produced_image = BootImage::parse(&produced_bytes).expect("产物不是合法 boot 镜像");
        let (produced_cpio, _) = extract_ramdisk(
            produced_image
                .get_blocks()
                .get_ramdisk()
                .expect("产物里没有 ramdisk"),
        )
        .expect("产物 ramdisk 解不开");
        assert!(
            !produced_cpio.exists(OFFICIAL_KSU_ENTRY),
            "产物里还留着别人的 KSU 模块条目 —— 根本没拆干净"
        );
        assert!(produced_cpio.exists("init"), "产物里的 `init` 丢了");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 造一张**最小的合法 boot v4 镜像**（头部 + 一份裸 cpio ramdisk），
    /// 专供上面那条"走完整流程"的单测：让 `restore()` 能真的
    /// 解析镜像 → 解 ramdisk → 反查归属 → 处置 → 重新打包 → 写文件。
    ///
    /// 头部布局照 `android-bootimg` 的 `BOOT_HEADER_V4`（小端，magic 占前 8 字节）：
    ///   `[0..8)` `ANDROID!` · `[8..12)` kernel_size · `[12..16)` ramdisk_size ·
    ///   `[16..20)` os_version · `[20..24)` header_size · `[24..40)` reserved ·
    ///   `[40..44)` header_version(=4) · `[44..1580)` cmdline · `[1580..1584)` signature_size。
    /// v3+ 的 page_size 固定 4096 → 头部整占一页，ramdisk 紧随其后。
    /// 裸 cpio 不是任何压缩格式 → `parse_compress_format` 判为 UNKNOWN → 原样读取。
    #[cfg(not(target_os = "android"))]
    fn synthetic_boot_image(ramdisk: &[u8]) -> Vec<u8> {
        const PAGE: usize = 4096;
        let mut img = vec![0u8; PAGE];
        img[0..8].copy_from_slice(b"ANDROID!");
        img[8..12].copy_from_slice(&0u32.to_le_bytes()); // kernel_size = 0（不需要内核）
        img[12..16].copy_from_slice(&(ramdisk.len() as u32).to_le_bytes());
        img[40..44].copy_from_slice(&4u32.to_le_bytes()); // header_version = 4
        img.extend_from_slice(ramdisk);
        img
    }

    /// v2.5 根因与修法的**可执行证明**：把上游 KernelSU 的那几行判据**逐字搬进来**，
    /// 对比"旧单条目布局"和"v2.5 双条目布局"在官方眼里的下场。
    ///
    /// 上游 `userspace/ksud/src/boot_patch.rs`（本地克隆 `~/projects/7kkernel/src` 实测）：
    ///
    /// ```text
    /// let is_kernelsu_patched = cpio.exists("kernelsu.ko");      // ← 唯一判据，不看别的
    /// if !is_kernelsu_patched && cpio.exists("init") {
    ///     cpio.mv("init", "init.real")?;                          // ← 就是这一步造成死循环
    /// }
    /// cpio.add("init", CpioEntry::regular(0o755, ksu_init));      // 官方 ksuinit
    /// cpio.add("kernelsu.ko", CpioEntry::regular(0o755, kernelsu_ko)); // 官方模块
    /// ```
    #[test]
    fn official_patch_would_not_stack_on_dual_marked_image() {
        const REAL_INIT: &[u8] = b"REAL_STOCK_INIT";
        const OUR_KSUINIT: &[u8] = b"OUR_KSUINIT";
        const OUR_MODULE: &[u8] = b"OUR_MODULE";

        // 官方那套动作（逐字照搬上游顺序）
        let official_patch = |cpio: &mut Cpio| {
            let is_kernelsu_patched = cpio.exists(OFFICIAL_KSU_ENTRY);
            if !is_kernelsu_patched && cpio.exists("init") {
                cpio.mv("init", "init.real").unwrap();
            }
            cpio.add(
                "init",
                CpioEntry::regular(0o755, Box::new(b"OFFICIAL_KSUINIT".to_vec())),
            )
            .unwrap();
            cpio.add(
                OFFICIAL_KSU_ENTRY,
                CpioEntry::regular(0o755, Box::new(b"OFFICIAL_MODULE".to_vec())),
            )
            .unwrap();
        };

        let init_of = |cpio: &Cpio, name: &str| -> Vec<u8> {
            cpio.entry_by_name(name)
                .unwrap_or_else(|| panic!("entry {name} missing"))
                .data()
                .unwrap()
                .to_vec()
        };

        // ── 负对照：v2.4 及更早（ramdisk 里**只有** `sevenk.ko`）──────────────
        let mut old_layout = Cpio::new();
        old_layout
            .add(
                "init",
                CpioEntry::regular(0o755, Box::new(OUR_KSUINIT.to_vec())),
            )
            .unwrap();
        old_layout
            .add(
                "init.real",
                CpioEntry::regular(0o755, Box::new(REAL_INIT.to_vec())),
            )
            .unwrap();
        old_layout
            .add(
                OURS_KSU_ENTRY,
                CpioEntry::regular(0o755, Box::new(OUR_MODULE.to_vec())),
            )
            .unwrap();
        assert_eq!(RamdiskState::of(&old_layout), RamdiskState::Ours);

        official_patch(&mut old_layout);
        // 真 init 被我们的 ksuinit 顶掉 → 开机后 ksuinit exec 自己 → 无限重启（用户实测）
        assert_eq!(
            init_of(&old_layout, "init.real"),
            OUR_KSUINIT,
            "旧布局下官方一定会 mv init -> init.real，把真 init 覆盖掉（这就是变砖）"
        );
        assert_ne!(init_of(&old_layout, "init.real"), REAL_INIT);

        // ── 修好之后：v2.5 双条目（`sevenk.ko` + `kernelsu.ko`）────────────────
        let mut new_layout = Cpio::new();
        new_layout
            .add(
                "init",
                CpioEntry::regular(0o755, Box::new(OUR_KSUINIT.to_vec())),
            )
            .unwrap();
        new_layout
            .add(
                "init.real",
                CpioEntry::regular(0o755, Box::new(REAL_INIT.to_vec())),
            )
            .unwrap();
        new_layout
            .add(
                OURS_KSU_ENTRY,
                CpioEntry::regular(0o755, Box::new(OUR_MODULE.to_vec())),
            )
            .unwrap();
        new_layout
            .add(
                OFFICIAL_KSU_ENTRY,
                CpioEntry::regular(0o755, Box::new(OUR_MODULE.to_vec())),
            )
            .unwrap();
        assert_eq!(RamdiskState::of(&new_layout), RamdiskState::Ours);

        official_patch(&mut new_layout);
        // 关键：官方看到 `kernelsu.ko` → 判"已打过补丁" → **不挪 init** → 真 init 原封不动
        assert_eq!(
            init_of(&new_layout, "init.real"),
            REAL_INIT,
            "双条目布局下官方不会再叠一层，真 init 必须保住"
        );
        // 官方自己的 init / 模块照常写进去（它接管这张镜像，这没问题：真 init 还在 → 能开机）
        assert_eq!(init_of(&new_layout, "init"), b"OFFICIAL_KSUINIT");
        assert_eq!(init_of(&new_layout, OFFICIAL_KSU_ENTRY), b"OFFICIAL_MODULE");
        // 我们的 `sevenk.ko` 会变成孤儿条目 —— 但**没人加载它**：
        // 官方 ksuinit 只 load `/kernelsu.ko`（已被换成官方模块），我们的 ksuinit 已经不在 `init` 上了。
        assert!(new_layout.exists(OURS_KSU_ENTRY));
        // 归属判定：官方接管后 `sevenk.ko` 仍在，所以**我们依然认得出这张镜像**（判 Ours）。
        // 这是刻意的取舍：我们自己的 restore 会连带清掉官方那份模块，但 `init.real` 里的真 init
        // 会被正常还回去 → **设备照样能开机**（不是砖）；反过来若不认，就只能靠用户手工救。
        assert_eq!(RamdiskState::of(&new_layout), RamdiskState::Ours);
    }

    /// ③ 离线镜像演练（**不刷机**）：拿"已经被 `kernelsu.ko` 打过补丁的镜像"走一遍**真正的**
    /// [`patch`]（只输出文件；宿主机没有 `--flash`，也绝不会碰任何分区）。
    ///
    /// 输入（取第一个存在的）：
    ///   · `$SEVENK_REHEARSAL_SRC`
    ///   · `~/projects/7kkernel/dist8/init_boot_a_7k_v08.img`
    ///     —— 项目 2026-09-12 的产物：ramdisk = `init` + 真模块 `kernelsu.ko`，
    ///     **正是官方 KernelSU 打完补丁的形状**（那个年代我们的条目名就是 `kernelsu.ko`）
    ///   · `~/Backups/7kimisu/init_boot-backup/init_boot_a_20260920-0314.img`
    ///   · `~/projects/7kkernel/dist3/init_boot_a_7k_v03.img`
    ///
    /// 输出目录 `$SEVENK_REHEARSAL_DIR`（默认系统临时目录）：`01_source.img`（软链/副本）、
    /// `02_official_like.img` + `02_official_like_ramdisk.cpio`、`03_product.img` +
    /// `03_product_ramdisk.cpio`（后两个是**裸 cpio**，可以用 `cpio -itv < …` 独立复核）。
    ///
    /// 跑法：`cargo test --offline -- --ignored --nocapture offline_rehearsal`
    #[test]
    #[ignore = "需要真实 boot 镜像；特意手动跑的离线演练，不进常规 cargo test"]
    #[cfg(not(target_os = "android"))]
    #[allow(clippy::too_many_lines)]
    fn offline_rehearsal_patch_official_image() {
        /// 假的"真 init"，用来验证它**没有**被我们/别人的 ksuinit 覆盖
        const REAL_INIT: &[u8] = b"7kimisu-rehearsal: REAL init placeholder\n";
        const KMI: &str = "android16-6.12";

        let out_dir =
            std::env::var_os("SEVENK_REHEARSAL_DIR").map_or_else(std::env::temp_dir, PathBuf::from);
        std::fs::create_dir_all(&out_dir).unwrap();

        let home = std::env::var("HOME").unwrap_or_default();
        let candidates: Vec<PathBuf> = std::env::var_os("SEVENK_REHEARSAL_SRC")
            .map(PathBuf::from)
            .into_iter()
            .chain([
                PathBuf::from(format!(
                    "{home}/projects/7kkernel/dist8/init_boot_a_7k_v08.img"
                )),
                PathBuf::from(format!(
                    "{home}/Backups/7kimisu/init_boot-backup/init_boot_a_20260920-0314.img"
                )),
                PathBuf::from(format!(
                    "{home}/projects/7kkernel/dist3/init_boot_a_7k_v03.img"
                )),
            ])
            .collect();
        let Some(src) = candidates.iter().find(|p| p.is_file()) else {
            println!("- rehearsal SKIPPED: 没找到可用的 boot 镜像；可用 SEVENK_REHEARSAL_SRC 指定");
            return;
        };
        println!("- rehearsal source : {}", src.display());
        println!("- rehearsal outdir : {}", out_dir.display());

        // ── 1. 读输入，确认它就是"别人用 kernelsu.ko 打过的镜像"────────────────
        let source_bytes = std::fs::read(src).unwrap();
        let source_image = BootImage::parse(&source_bytes).unwrap();
        let (mut cpio, vendor_idx) = extract_ramdisk(
            source_image
                .get_blocks()
                .get_ramdisk()
                .expect("source image has no ramdisk"),
        )
        .unwrap();

        assert!(cpio.exists(OFFICIAL_KSU_ENTRY), "输入里没有 kernelsu.ko");
        assert!(!cpio.exists(OURS_KSU_ENTRY), "输入里已经有 sevenk.ko");
        // v2.5：官方 KernelSU 的镜像 = `kernelsu.ko` + `init.real` → **Compatible**（允许直接安装）
        // v2.6：这就是"在官方 KernelSU 上点直接安装"要走的那条路 —— 写分区闸必须放行。
        assert_eq!(RamdiskState::of(&cpio), RamdiskState::Compatible);
        assert!(!must_refuse_unsafe_flash(
            RamdiskState::Compatible,
            true,
            false
        ));
        // v2.6：只读查询命令对同一张镜像的输出（App 靠这个开按钮）
        assert_eq!(query_ramdisk_state(Some(src.as_path())), "compatible");
        let foreign_module_len = cpio
            .entry_by_name(OFFICIAL_KSU_ENTRY)
            .unwrap()
            .data()
            .unwrap()
            .len();
        assert!(
            foreign_module_len > 10_000,
            "输入里的 kernelsu.ko 应该是真模块（实测 {foreign_module_len} 字节）"
        );
        println!(
            "- 输入状态        : Compatible（官方 KSU 布局：kernelsu.ko = {foreign_module_len} 字节真模块 + init.real）"
        );
        print_cpio("输入 ramdisk", &cpio);

        // ── 2. 确认/造出"真 init"（`init.real`）──────────────────────────────
        //      源镜像里**本来就有** `init.real`（设备 dump 出来的真 init，几 MB）时直接用它 ——
        //      这比占位符更有说服力；没有才注入一个可辨认的占位符。
        let existing_real_init = cpio
            .entry_by_name("init.real")
            .map(|entry| entry.data().unwrap().to_vec());
        let expected_real_init: Vec<u8> = existing_real_init.map_or_else(
            || {
                println!("- 源镜像没有 init.real，注入可辨认占位符作为\"真 init\"");
                cpio.add(
                    "init.real",
                    CpioEntry::regular(0o755, Box::new(REAL_INIT.to_vec())),
                )
                .unwrap();
                REAL_INIT.to_vec()
            },
            |bytes| {
                println!(
                    "- 源镜像自带 init.real（{} 字节）—— 它就是\"真 init\"",
                    bytes.len()
                );
                bytes
            },
        );
        if !cpio.exists("init") {
            cpio.add(
                "init",
                CpioEntry::regular(0o755, Box::new(b"OLD-KSUINIT".to_vec())),
            )
            .unwrap();
        }

        let mut official_ramdisk = Vec::new();
        cpio.dump(&mut official_ramdisk).unwrap();
        std::fs::write(
            out_dir.join("02_official_like_ramdisk.cpio"),
            &official_ramdisk,
        )
        .unwrap();

        let mut patcher = BootImagePatchOption::new(&source_image);
        if let Some(idx) = vendor_idx {
            patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(official_ramdisk)), false);
        } else {
            patcher.replace_ramdisk(Box::new(Cursor::new(official_ramdisk)), false);
        }
        let mut official_buf = Cursor::new(Vec::with_capacity(source_image.get_size()));
        patcher.patch(&mut official_buf).unwrap();
        let official_like = out_dir.join("02_official_like.img");
        std::fs::write(&official_like, official_buf.into_inner()).unwrap();
        drop(source_image);
        println!(
            "- 造好\"官方打过补丁 + 有真 init\"的镜像: {}",
            official_like.display()
        );

        // ── 3. 走**真正的** `patch()`（离线，只写文件）───────────────────────
        patch(BootPatchArgs {
            boot: Some(official_like),
            kernel: None,
            module: None,
            init: None,
            kmi: Some(KMI.to_owned()),
            out: Some(out_dir.clone()),
            out_name: Some("03_product.img".to_owned()),
            cmdline: None,
            allow_shell: false,
            enable_adbd: false,
            adb_debug_prop: None,
            no_install: false,
            no_custom_rc: false,
            arch: "aarch64".to_owned(),
            ramdisk: false,
        })
        .unwrap();

        // ── 4. 解包产物逐条核对 ────────────────────────────────────────────
        let product_path = out_dir.join("03_product.img");
        let product_bytes = std::fs::read(&product_path).unwrap();
        let product_image = BootImage::parse(&product_bytes).unwrap();
        let (product_cpio, _) = extract_ramdisk(
            product_image
                .get_blocks()
                .get_ramdisk()
                .expect("product has no ramdisk"),
        )
        .unwrap();
        let mut product_ramdisk = Vec::new();
        product_cpio.dump(&mut product_ramdisk).unwrap();
        std::fs::write(out_dir.join("03_product_ramdisk.cpio"), &product_ramdisk).unwrap();
        println!("- 产物            : {}", product_path.display());
        print_cpio("产物 ramdisk", &product_cpio);

        // (1) 官方名标记在
        assert!(
            product_cpio.exists(OFFICIAL_KSU_ENTRY),
            "产物里没有 kernelsu.ko —— 官方 KSU 会认为没打过补丁！"
        );
        // (2) 我们自己的条目也在
        assert!(product_cpio.exists(OURS_KSU_ENTRY));
        // (3) 两条都是**同一份真模块**（ELF、非空、非文本标记）
        let ours = product_cpio
            .entry_by_name(OURS_KSU_ENTRY)
            .unwrap()
            .data()
            .unwrap();
        let official = product_cpio
            .entry_by_name(OFFICIAL_KSU_ENTRY)
            .unwrap()
            .data()
            .unwrap();
        assert_eq!(ours, official, "两个名字必须放同一份真模块");
        assert!(ours.starts_with(b"\x7fELF"), "模块必须是 ELF（真模块）");
        assert!(ours.len() > 10_000);
        // (4) **真 init 没被破坏**（逐字节相同）
        assert_eq!(
            product_cpio
                .entry_by_name("init.real")
                .unwrap()
                .data()
                .unwrap(),
            expected_real_init.as_slice(),
            "真 init 被覆盖了！"
        );
        // (5) 归属与 restore 判据
        assert_eq!(RamdiskState::of(&product_cpio), RamdiskState::Ours);
        assert!(is_restorable(RamdiskState::of(&product_cpio)));

        // (6) 模拟官方**再打一层**：照搬上游判据，真 init 必须原封不动
        let mut after_official = product_cpio;
        let is_kernelsu_patched = after_official.exists(OFFICIAL_KSU_ENTRY);
        assert!(is_kernelsu_patched, "官方一定会判\"已打过补丁\"");
        if !is_kernelsu_patched && after_official.exists("init") {
            after_official.mv("init", "init.real").unwrap();
        }
        after_official
            .add(
                "init",
                CpioEntry::regular(0o755, Box::new(b"OFFICIAL_KSUINIT".to_vec())),
            )
            .unwrap();
        assert_eq!(
            after_official
                .entry_by_name("init.real")
                .unwrap()
                .data()
                .unwrap(),
            expected_real_init.as_slice(),
            "官方再打一层后，真 init 必须原封不动（这就是\"想怎么转就怎么转\"）"
        );

        println!(
            "- rehearsal PASSED ✅（产品文件都在 {}）",
            out_dir.display()
        );
    }

    fn print_cpio(title: &str, cpio: &Cpio) {
        println!("- {title} 条目：");
        for (name, entry) in cpio.entries() {
            println!("-   {name:<16} {} 字节", entry.len());
        }
    }
}
