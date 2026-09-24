#![allow(clippy::unreadable_literal)]
use anyhow::{Result, bail};

use crate::ksu_uapi;
use std::fs;
use std::io;
use std::os::fd::RawFd;
use std::sync::OnceLock;

// ══════════════════════════════════════════════════════════════════════
// seccomp / SIGSYS 防线（2026-09-21，v2.14）
//
// 背景（用户提供的 vivo V2507A tombstone 实锤）：
//   `libksud.so feature check <name>` → … → [`init_driver_fd`] 会在**本进程里**发一次
//   `syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd)`（KernelSU 超级调用的 kprobe 入口）。
//   某些 ROM（vivo/OriginOS、部分 OPPO、MIUI）的 seccomp 策略把这个系统调用的动作配成
//   **SECCOMP_RET_KILL_PROCESS**：内核直接杀进程，`SIGSYS` 根本来不及交给用户态 ——
//   表现就是 tombstone（signal 31 / code 1）+「打开或某个页面闪退」，且**只有一部分机型**
//   中招（取决于该 ROM 的 seccomp 配置，与版本号无关）。
//
// 两条必须记住的结论：
//   ① **装 SIGSYS 处理器救不了 KILL_PROCESS**。下面的 [sigsys_handler] 只能接住
//      `SECCOMP_RET_TRAP` 型 ROM（那种会先把信号交给用户态，未处理才 core dump）。
//      真正安全的做法只有一个：**让这个系统调用不在本进程里发生**。
//   ② v2.14 起，所有「reboot 魔数自救」的尝试都放到 **fork 出来的短命子进程** 里
//      （见 [install_driver_fd_isolated]）：子进程被 seccomp 杀掉，只是父进程 waitpid
//      拿到的一个信号状态，父进程照常活着；读退出状态就知道「这条 ROM 不允许」。
//
// [sigsys_handler] 保留为**第二层**兜底：TRAP 型 ROM 上，任何被 seccomp 拦下的系统调用
// 都会被就地改成 `-EPERM` 返回，而不是让 ksud 崩掉。
// ══════════════════════════════════════════════════════════════════════

const SYS_SECCOMP: libc::c_int = 1;

/// 「本进程里有系统调用被 seccomp 以 TRAP 方式拦下过」。
///
/// 只写不读是不行的（edition 2024 下 `dead_code` 会报警），
/// 所以 [`init_driver_fd`] 会在探测出「没拿到句柄」时读它来区分
/// 「内核没装」和「被 seccomp 拦了」——这就是给用户/上报纸告看的诊断信息。
static SIGSYS_TRAPPED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// 信号处理器里**只做异步信号安全的事**：写一个原子布尔 + 改 ucontext 的返回值寄存器。
/// 不打日志、不分配、不取锁（这些在信号上下文里都可能死锁）。
extern "C" fn sigsys_handler(
    _sig: libc::c_int,
    info: *mut libc::siginfo_t,
    ctx: *mut libc::c_void,
) {
    unsafe {
        if info.is_null() || ctx.is_null() || (*info).si_code != SYS_SECCOMP {
            return;
        }
        SIGSYS_TRAPPED.store(true, std::sync::atomic::Ordering::Relaxed);

        let ucontext = ctx.cast::<libc::ucontext_t>();
        #[cfg(target_arch = "aarch64")]
        {
            (*ucontext).uc_mcontext.regs[0] = (-libc::EPERM) as u64;
        }
        #[cfg(target_arch = "x86_64")]
        {
            let rax = libc::REG_RAX as usize;
            (*ucontext).uc_mcontext.gregs[rax] = i64::from(-libc::EPERM);
        }
    }
}

pub fn setup_sigsys_handler() {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_flags = libc::SA_SIGINFO;
        sa.sa_sigaction = sigsys_handler as *const () as usize;
        libc::sigemptyset(std::ptr::addr_of_mut!(sa.sa_mask));
        if libc::sigaction(libc::SIGSYS, std::ptr::addr_of!(sa), std::ptr::null_mut()) != 0 {
            let error = std::io::Error::last_os_error();
            log::warn!("Failed to set SIGSYS handler: {error}");
        }
    }
}

const DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver]";
const SU_DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver_su]";

// Global driver fd cache
static DRIVER_FD: OnceLock<RawFd> = OnceLock::new();
static INFO_CACHE: OnceLock<ksu_uapi::ksu_get_info_cmd> = OnceLock::new();

pub fn scan_driver_fd() -> io::Result<Option<RawFd>> {
    let fd_dir = fs::read_dir("/proc/self/fd")?;
    let mut driver_fd = None;

    for entry in fd_dir.flatten() {
        if let Ok(fd_num) = entry.file_name().to_string_lossy().parse::<i32>() {
            let link_path = format!("/proc/self/fd/{fd_num}");
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if target_str == SU_DRIVER_FD_NAME {
                    return Ok(Some(fd_num));
                }
                if target_str == DRIVER_FD_NAME {
                    driver_fd = Some(fd_num);
                }
            }
        }
    }

    Ok(driver_fd)
}

pub fn claim_inherited_driver_fd() -> io::Result<()> {
    if DRIVER_FD.get().is_none()
        && let Some(fd) = scan_driver_fd()?
    {
        let _ = DRIVER_FD.set(fd);
    }
    Ok(())
}

// ══════════════════════════════════════════════════════════════════════
// 「reboot 魔数自救」——**必须**在独立子进程里做（v2.14 的核心修复）
// ══════════════════════════════════════════════════════════════════════

/// 一次自救探测的结论。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InstallFdProbe {
    /// 子进程活着，并把内核装好的驱动句柄用 `SCM_RIGHTS` 传回来了
    Got(RawFd),
    /// 子进程活着，但内核没给句柄（没装内核 / 不是管理器 / 被内核拒绝 / 被 TRAP 改成 EPERM）
    NoFd,
    /// 子进程被 seccomp 用 SIGSYS 杀掉了 —— **本进程绝不能自己再发这个系统调用**
    Blocked,
    /// fork/socketpair 等本机原因失败，什么都没做
    Failed,
}

/// `SCM_RIGHTS` 辅助控制消息的布局（在 64 位 Linux/Android 上分别是 16 / 20 / 24）。
///
/// 刻意不用 libc 的 `CMSG_*` 宏：它们在各个 target 上有的签名不同、有的不是 `const fn`，
/// 这里用 `size_of` 自己算，跨平台且编译期就能校验。
const CMSG_HDR_LEN: usize = std::mem::size_of::<libc::cmsghdr>();
const CMSG_LEN_FD: usize = CMSG_HDR_LEN + std::mem::size_of::<RawFd>();
const CMSG_ALIGN: usize = std::mem::size_of::<usize>();
const CMSG_SPACE_FD: usize = CMSG_LEN_FD.div_ceil(CMSG_ALIGN) * CMSG_ALIGN;

/// 一次「reboot 魔数自救」探测最多等多久（毫秒）。见 [`install_driver_fd_isolated`] 父进程侧。
const PROBE_TIMEOUT_MS: libc::c_int = 2000;

/// 把 `fd` 用 `SCM_RIGHTS` 经 `sock`（AF_UNIX socketpair）送给父进程。
///
/// ⚠️ 只在 fork 出来的子进程里调用：**不做任何堆分配**，iov/msghdr/cmsg 全在栈上
/// （父进程可能是多线程的，fork 之后这里只能碰异步信号安全的东西）。
///
/// # Safety
/// `sock` 必须是已连接的 AF_UNIX socket，`fd` 必须是有效的文件描述符。
unsafe fn send_fd(sock: RawFd, fd: RawFd) {
    unsafe {
        let mut byte = 0u8;
        let mut iov = libc::iovec {
            iov_base: std::ptr::addr_of_mut!(byte).cast(),
            iov_len: 1,
        };
        let mut cmsgbuf = [0u8; CMSG_SPACE_FD];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = std::ptr::addr_of_mut!(iov);
        msg.msg_iovlen = 1;
        msg.msg_control = cmsgbuf.as_mut_ptr().cast();
        msg.msg_controllen = cmsgbuf.len() as _;

        let cmsg = libc::CMSG_FIRSTHDR(&raw const msg);
        if cmsg.is_null() {
            return;
        }
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = CMSG_LEN_FD as _;
        std::ptr::copy_nonoverlapping(
            std::ptr::addr_of!(fd).cast::<u8>(),
            libc::CMSG_DATA(cmsg),
            std::mem::size_of::<RawFd>(),
        );
        // 失败也不管：父进程读不到句柄就会按「没有内核访问」优雅降级
        let _ = libc::sendmsg(sock, std::ptr::addr_of!(msg), 0);
    }
}

/// 从 `sock` 收一个 `SCM_RIGHTS` 送来的句柄。
///
/// 子进程若被信号杀死，socket 写端随之关闭 → `recvmsg` 立刻返回 0（**不会挂住**），
/// 这正是「子进程被 SIGSYS 杀掉」能被父进程察觉的机制。
///
/// # Safety
/// `sock` 必须是已连接的 AF_UNIX socket。
unsafe fn recv_fd(sock: RawFd) -> Option<RawFd> {
    unsafe {
        let mut byte = 0u8;
        let mut iov = libc::iovec {
            iov_base: std::ptr::addr_of_mut!(byte).cast(),
            iov_len: 1,
        };
        let mut cmsgbuf = [0u8; CMSG_SPACE_FD];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = std::ptr::addr_of_mut!(iov);
        msg.msg_iovlen = 1;
        msg.msg_control = cmsgbuf.as_mut_ptr().cast();
        msg.msg_controllen = cmsgbuf.len() as _;

        // MSG_CMSG_CLOEXEC：收到的句柄带 close-on-exec —— 与内核自己装 fd 时用的
        // O_CLOEXEC 一致（kernel/supercall/supercall.c: ksu_install_fd → O_CLOEXEC）。
        if libc::recvmsg(sock, std::ptr::addr_of_mut!(msg), libc::MSG_CMSG_CLOEXEC) <= 0 {
            return None;
        }
        let cmsg = libc::CMSG_FIRSTHDR(&raw const msg);
        if cmsg.is_null()
            || (*cmsg).cmsg_level != libc::SOL_SOCKET
            || (*cmsg).cmsg_type != libc::SCM_RIGHTS
        {
            return None;
        }
        let mut fd: RawFd = -1;
        std::ptr::copy_nonoverlapping(
            libc::CMSG_DATA(cmsg),
            std::ptr::addr_of_mut!(fd).cast::<u8>(),
            std::mem::size_of::<RawFd>(),
        );
        (fd >= 0).then_some(fd)
    }
}

/// 在**独立子进程**里试一次「reboot 魔数自救」，并把内核装好的句柄带回来。
///
/// 为什么非要 fork（v2.14，用户 vivo tombstone 实锤）：
///   vivo/OriginOS 等 ROM 的 seccomp 把 `reboot(2)` 的动作配成 `SECCOMP_RET_KILL_PROCESS`，
///   内核**直接杀掉调用方整个进程**、`SIGSYS` 根本不交给用户态 —— 装信号处理器也没用。
///   放到子进程里之后：
///     · 子进程被 SIGSYS 杀掉 → 父进程 `waitpid` 得到 `WIFSIGNALED && WTERMSIG == SIGSYS`
///       → 归类为 [`InstallFdProbe::Blocked`] → **优雅降级**（返回 None，报"没有内核访问"）；
///     · 父进程自始至终**没有**发过这个系统调用，所以任何 ROM 都杀不掉它。
fn install_driver_fd_isolated() -> InstallFdProbe {
    let mut sv = [-1i32; 2];
    // SOCK_CLOEXEC：这对 socket 不该被 exec 出去的子进程继承
    let sp = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC,
            0,
            sv.as_mut_ptr(),
        )
    };
    if sp != 0 {
        return InstallFdProbe::Failed;
    }
    let [parent_sock, child_sock] = sv;

    let pid = unsafe { libc::fork() };
    if pid == 0 {
        // ── 子进程：只做系统调用 + _exit，绝不进入 Rust 的分配/日志/加锁代码 ──
        unsafe {
            libc::close(parent_sock);
            let mut fd: RawFd = -1;
            // 就是那个会被部分 ROM 用 seccomp 杀掉的调用；在这里被杀的代价 = 一个短命子进程
            libc::syscall(
                libc::SYS_reboot,
                u64::from(ksu_uapi::KSU_INSTALL_MAGIC1),
                u64::from(ksu_uapi::KSU_INSTALL_MAGIC2),
                0u64,
                std::ptr::addr_of_mut!(fd),
            );
            if fd >= 0 {
                send_fd(child_sock, fd);
            }
            libc::close(child_sock);
            libc::_exit(0);
        }
    }

    unsafe {
        libc::close(child_sock);
        if pid < 0 {
            libc::close(parent_sock);
            return InstallFdProbe::Failed;
        }

        // ── 父进程侧：给这次探测一个**上限**（[PROBE_TIMEOUT_MS]）──────────────
        // 子进程理论上只做一次系统调用就 `_exit`；加超时纯粹是防"内核 kprobe 卡住"
        // 这种极端情况把 ksud（进而把管理器界面）一起拖死 ——
        // 超时就杀掉子进程、按"探测失败"优雅降级，绝不无界等待。
        //
        // 用 poll 而不是直接 recvmsg：子进程被 seccomp 杀掉时 socket 写端随之关闭，
        // poll 会立刻以 POLLHUP 返回（**不会挂住**），这正是"子进程死了"能被察觉的机制。
        let fl = libc::fcntl(parent_sock, libc::F_GETFL);
        if fl >= 0 {
            libc::fcntl(parent_sock, libc::F_SETFL, fl | libc::O_NONBLOCK);
        }
        let mut pfd = libc::pollfd {
            fd: parent_sock,
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = libc::poll(std::ptr::addr_of_mut!(pfd), 1, PROBE_TIMEOUT_MS);
        if ready <= 0 {
            libc::kill(pid, libc::SIGKILL);
        }
        let received = if ready > 0 { recv_fd(parent_sock) } else { None };
        libc::close(parent_sock);
        let mut status: libc::c_int = 0;
        libc::waitpid(pid, std::ptr::addr_of_mut!(status), 0);

        if ready <= 0 {
            log::warn!("driver fd install probe timed out after {PROBE_TIMEOUT_MS}ms; killed the child");
            return InstallFdProbe::Failed;
        }
        if libc::WIFSIGNALED(status) {
            let sig = libc::WTERMSIG(status);
            if sig == libc::SIGSYS {
                return InstallFdProbe::Blocked;
            }
            log::warn!("driver fd probe child killed by signal {sig}");
            return InstallFdProbe::Failed;
        }
        // 子进程活着但什么都没送回来 = 内核没给句柄（没装内核 / 不是管理器 / 被改成 EPERM）
        received.map_or(InstallFdProbe::NoFd, InstallFdProbe::Got)
    }
}

/// 读 `/proc/self/status` 的 `Seccomp:` 字段（0=未启用 1=strict 2=filter）。
///
/// ⚠️ **只当诊断/上报用，绝不当门禁**：Android 上几乎人人都是 2（有过滤器），
///    而绝大多数 ROM 是**放行** `reboot(2)` 的 —— 拿它去跳过探测只会把好机型一起误伤，
///    把"能自救"变成"永远显示未安装"。真正可靠的判据只有一个：
///    在子进程里实测一次（[`install_driver_fd_isolated`]）。
pub fn seccomp_mode() -> Option<u32> {
    let status = fs::read_to_string("/proc/self/status").ok()?;
    status.lines().find_map(|line| {
        line.strip_prefix("Seccomp:")
            .and_then(|v| v.trim().parse::<u32>().ok())
    })
}

// Get cached driver fd
fn init_driver_fd() -> Option<RawFd> {
    // ① 先扫 /proc/self/fd：内核可能已经在 setresuid 那一刻把句柄装进来了 —— 这条路零风险、零系统调用
    if let Ok(Some(fd)) = scan_driver_fd() {
        return Some(fd);
    }

    // ② 没有 → 去**独立子进程**里试一次魔数自救（本进程绝不发这个系统调用）
    match install_driver_fd_isolated() {
        InstallFdProbe::Got(fd) => {
            log::info!("driver fd obtained from isolated install probe: {fd}");
            Some(fd)
        }
        InstallFdProbe::NoFd => {
            // 子进程活着但没拿到句柄：内核没装 / 本 App 不是管理器。
            // 再用 SIGSYS_TRAPPED 区分「TRAP 型 seccomp 拦下了」和「纯粹没有内核」。
            if SIGSYS_TRAPPED.load(std::sync::atomic::Ordering::Relaxed) {
                log::warn!("KernelSU driver install syscall was intercepted by seccomp (TRAP)");
                eprintln!(
                    "KernelSU driver install syscall was blocked by seccomp (Seccomp mode {:?}); \
                     continuing without kernel access",
                    seccomp_mode()
                );
            } else {
                log::info!("no KernelSU driver fd (kernel not installed or not the manager)");
            }
            None
        }
        InstallFdProbe::Blocked => {
            // 🔴 就是 vivo/OriginOS 这条路：seccomp 直接杀进程。
            //    子进程已经替我们死过一次了 —— 父进程照常活着，报"没有内核访问"。
            log::error!(
                "KernelSU driver install syscall was blocked by seccomp (KILL_PROCESS, Seccomp mode {:?}); \
                 continuing without kernel access",
                seccomp_mode()
            );
            eprintln!(
                "KernelSU driver install syscall was blocked by seccomp (Seccomp mode {:?}); \
                 continuing without kernel access",
                seccomp_mode()
            );
            None
        }
        InstallFdProbe::Failed => {
            log::warn!("driver fd install probe could not run (fork/socketpair failed)");
            None
        }
    }
}

// ioctl wrapper using libc
pub fn ksuctl<T>(request: u32, arg: *mut T) -> Result<i32> {
    use std::io;

    let fd = *DRIVER_FD.get_or_init(|| init_driver_fd().unwrap_or(-1));
    if fd < 0 {
        bail!("could not retrieve kernelsu driver fd")
    }
    unsafe {
        let ret = libc::ioctl(fd as libc::c_int, request as i32, arg);
        if ret < 0 {
            bail!("ksuctl failed: {}", io::Error::last_os_error())
        }
        Ok(ret)
    }
}

// API implementations
pub fn get_info() -> ksu_uapi::ksu_get_info_cmd {
    *INFO_CACHE.get_or_init(|| {
        let mut cmd = ksu_uapi::ksu_get_info_cmd {
            version: 0,
            flags: 0,
            features: 0,
            uapi_version: 0,
        };
        if ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut cmd).is_err() {
            let _ = ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO_LEGACY, &raw mut cmd);
        }
        cmd
    })
}

pub fn get_version() -> i32 {
    get_info().version as i32
}

pub fn is_late_load() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LATE_LOAD != 0
}

pub fn is_lkm() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LKM != 0
}

pub const fn uapi_version() -> u32 {
    ksu_uapi::KERNEL_SU_UAPI_VERSION
}

pub fn runtime_mode() -> &'static str {
    if is_late_load() {
        "late-load"
    } else if is_lkm() {
        "lkm"
    } else {
        "built-in"
    }
}

pub fn ensure_uapi_version_matched() -> anyhow::Result<()> {
    let kernel_uapi = get_info().uapi_version;
    let userspace_uapi = uapi_version();
    // UAPI 的升级只做"新增":新内核向后兼容旧 ksud。
    // 所以只要【内核 UAPI >= ksud UAPI】即可;只有内核【过旧】才报错。
    // (注意:内核走 legacy 回退时 uapi_version 为 0,会落到这里 → 正确报错)
    if kernel_uapi < userspace_uapi {
        bail!(
            "UAPI too old: kernel={kernel_uapi}, ksud={userspace_uapi}. Please update the kernel!"
        );
    }
    Ok(())
}

pub fn grant_root() -> Result<()> {
    ksuctl(ksu_uapi::KSU_IOCTL_GRANT_ROOT, std::ptr::null_mut::<u8>())?;
    Ok(())
}

fn report_event(event: u32) {
    let mut cmd = ksu_uapi::ksu_report_event_cmd { event };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd);
}

pub fn report_post_fs_data() {
    report_event(ksu_uapi::EVENT_POST_FS_DATA);
}

pub fn report_boot_complete() {
    report_event(ksu_uapi::EVENT_BOOT_COMPLETED);
}

pub fn report_module_mounted() {
    report_event(ksu_uapi::EVENT_MODULE_MOUNTED);
}

pub fn check_kernel_safemode() -> bool {
    let mut cmd = ksu_uapi::ksu_check_safemode_cmd { in_safe_mode: 0 };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_CHECK_SAFEMODE, &raw mut cmd);
    cmd.in_safe_mode != 0
}

pub fn set_sepolicy(payload: *const u8, payload_len: u64) -> Result<i32> {
    let mut ioctl_cmd = crate::ksu_uapi::ksu_set_sepolicy_cmd {
        data_len: payload_len,
        data: payload as u64,
    };

    ksuctl(ksu_uapi::KSU_IOCTL_SET_SEPOLICY, &raw mut ioctl_cmd)
}

/// Get feature value and support status from kernel
/// Returns (value, supported)
pub fn get_feature(feature_id: u32) -> Result<(u64, bool)> {
    let mut cmd = ksu_uapi::ksu_get_feature_cmd {
        feature_id,
        value: 0,
        supported: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_FEATURE, &raw mut cmd)?;
    Ok((cmd.value, cmd.supported != 0))
}

/// Set feature value in kernel
pub fn set_feature(feature_id: u32, value: u64) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_feature_cmd { feature_id, value };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_FEATURE, &raw mut cmd)?;
    Ok(())
}

pub fn get_wrapped_fd(fd: RawFd) -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_wrapper_fd_cmd {
        fd: fd as u32,
        flags: 0,
    };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_WRAPPER_FD, &raw mut cmd)?;
    Ok(result)
}

pub fn get_sulog_fd() -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_sulog_fd_cmd { flags: 0 };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_SULOG_FD, &raw mut cmd)?;
    Ok(result)
}

/// Get mark status for a process (pid=0 returns total marked count)
pub fn mark_get(pid: i32) -> Result<u32> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_GET,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(cmd.result)
}

/// Mark a process (pid=0 marks all processes)
pub fn mark_set(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_MARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Unmark a process (pid=0 unmarks all processes)
pub fn mark_unset(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_UNMARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Refresh mark for all running processes
pub fn mark_refresh() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_REFRESH,
        pid: 0,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

pub fn nuke_ext4_sysfs(mnt: &str) -> anyhow::Result<()> {
    let c_mnt = std::ffi::CString::new(mnt)?;
    let mut ioctl_cmd = ksu_uapi::ksu_nuke_ext4_sysfs_cmd {
        arg: c_mnt.as_ptr() as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_NUKE_EXT4_SYSFS, &raw mut ioctl_cmd)?;
    Ok(())
}

/// Wipe all entries from umount list
pub fn umount_list_wipe() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: 0,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_WIPE,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Add mount point to umount list
pub fn umount_list_add(path: &str, flags: u32) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags,
        mode: ksu_uapi::KSU_UMOUNT_ADD,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Delete mount point from umount list
pub fn umount_list_del(path: &str) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_DEL,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Set current process's process group to init_group (pgid = 0)
pub fn set_init_pgrp() -> Result<()> {
    ksuctl(
        ksu_uapi::KSU_IOCTL_SET_INIT_PGRP,
        std::ptr::null_mut::<u8>(),
    )?;
    Ok(())
}

pub fn set_ksu_no_new_privs() -> anyhow::Result<()> {
    let result = ksuctl(
        ksu_uapi::KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT,
        std::ptr::null_mut::<u8>(),
    )?;
    if result != 0 {
        bail!("unexpected result: {result}");
    }
    Ok(())
}


/// 已授权的 uid 列表（网页管理器的超级用户页 / 计数用）
///
/// 注意：内核的 `ksu_get_allow_list(allow=true)` 只回 **allow_su == true** 的条目，
/// 且自动排除管理器自己（`is_uid_manager`）—— 所以这个列表就是"授权了 root 的 App"。
pub fn allow_list_uids() -> Vec<u32> {
    list_uids(true)
}

/// 有"非 root 配置"的 uid 列表（allow_su == false 的那些条目）。
///
/// 用户可能给某个没授权的 App 单独设了「卸载模块」这类非 root 策略，
/// 网页列表要把它们标成"已配置"，否则界面上看起来像没设置过。
/// 里面会混进默认非 root 模板（key = "$"，uid 9999），由调用方按包名过滤掉。
pub fn deny_list_uids() -> Vec<u32> {
    list_uids(false)
}

fn list_uids(allow: bool) -> Vec<u32> {
    use ksu_uapi::{ksu_new_get_allow_list_cmd, KSU_IOCTL_NEW_GET_ALLOW_LIST, KSU_IOCTL_NEW_GET_DENY_LIST};
    let req = if allow {
        KSU_IOCTL_NEW_GET_ALLOW_LIST
    } else {
        KSU_IOCTL_NEW_GET_DENY_LIST
    };
    // 先问总数
    let mut probe: ksu_new_get_allow_list_cmd = unsafe { std::mem::zeroed() };
    probe.count = 0;
    if ksuctl(req, &raw mut probe).is_err() {
        return Vec::new();
    }
    let total = probe.total_count as usize;
    if total == 0 {
        return Vec::new();
    }
    // 结构体尾部是柔性数组（uids[0]），所以手工多分配 total*4 字节。
    // ⚠️ 必须按 u32 分配：Vec<u8> 只保证 1 字节对齐，转成结构体指针后写字段就是"错位写"
    //    （clippy 的 cast_ptr_alignment 报的就是这个 —— 是真的隐患，不是风格问题）
    let base = std::mem::size_of::<ksu_new_get_allow_list_cmd>();
    let total = total.min(4096);
    let need = base + total * std::mem::size_of::<u32>();
    let mut buf: Vec<u32> = vec![0; need.div_ceil(std::mem::size_of::<u32>())];
    let cmd = buf.as_mut_ptr().cast::<ksu_new_get_allow_list_cmd>();
    unsafe {
        (*cmd).count = total as u16;
        (*cmd).total_count = 0;
    }
    if ksuctl(req, cmd).is_err() {
        return Vec::new();
    }
    let n = unsafe { (*cmd).count as usize }.min(total);
    let ptr = unsafe { (*cmd).uids.as_ptr() };
    unsafe { std::slice::from_raw_parts(ptr, n) }.to_vec()
}

/// 读"管理器 App 的 appid"（内核允许 root 调这个）
pub fn manager_appid() -> anyhow::Result<u32> {
    let mut cmd: ksu_uapi::ksu_get_manager_appid_cmd = unsafe { std::mem::zeroed() };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID, &raw mut cmd)?;
    Ok(cmd.appid)
}

/// 以「管理器 App 的 uid + 带管理器权限的 fd」执行一次隐身 ioctl。
///
/// 为什么要这么绕（2026-09-17 在设备上逐步定位）：
///   1. 内核里 `STEALTH_GET/SET` 的权限是 `only_manager`
///      （`current_uid() % 100000 == 管理器 appid`）—— 守护进程是 root，直接被拒；
///   2. 于是 fork 一个子进程 `setresuid` 成管理器 uid；
///   3. 但**光切 uid 还不够**：句柄（fd）本身也带权限位。守护进程的 fd 是从
///      App 的 root 会话继承来的（权限 0），所以还是 EPERM；
///   4. 关键点：内核在 `setresuid(管理器uid)` 那一刻会**给这个进程新装一个
///      带管理器权限的 `[ksu_driver]` fd**（hook/setuid_hook.c）。
///      所以子进程里：先把继承来的旧 fd 关掉，再重新扫一遍 /proc/self/fd，
///      拿到的就是那个"有权限"的新 fd —— 用它调 ioctl 才通。
///
/// 子进程里只做 syscall 与 _exit，不做堆分配（多线程进程 fork 后的安全做法）。
#[allow(clippy::comparison_chain)] // fork 的父子判断用 if/else if 最直观；子进程分支以 _exit 结束，塞进 match 反而更难读
fn stealth_ioctl_as_manager(enabled: Option<bool>) -> anyhow::Result<Option<bool>> {
    let appid = manager_appid()?;
    if appid == u32::MAX || appid == 0 {
        anyhow::bail!("拿不到管理器 appid");
    }
    let inherited = DRIVER_FD.get().copied().unwrap_or(-1);

    unsafe {
        let pid = libc::fork();
        if pid == 0 {
            // ── 子进程 ──
            if libc::setresuid(appid, appid, appid) != 0 {
                libc::_exit(10);
            }
            // 关掉继承来的旧句柄，免得扫到它
            if inherited >= 0 {
                libc::close(inherited);
            }
            // setresuid 时内核给我们装的新句柄
            let Ok(Some(fd)) = scan_driver_fd() else {
                libc::_exit(11);
            };
            // 已经在外面那层 unsafe 里了，这里不用再包一层
            let mut cmd: ksu_uapi::ksu_stealth_cmd = std::mem::zeroed();
            // Some(开/关) → SET；None → GET（读当前状态）
            let op = enabled.map_or(ksu_uapi::KSU_IOCTL_STEALTH_GET, |v| {
                cmd.enabled = u8::from(v);
                ksu_uapi::KSU_IOCTL_STEALTH_SET
            });
            let ret = libc::ioctl(fd, op as libc::c_int, &mut cmd);
            if ret != 0 {
                libc::_exit(12);
            }
            libc::_exit(i32::from(cmd.enabled == 0));
        } else if pid > 0 {
            let mut status: libc::c_int = 0;
            libc::waitpid(pid, &raw mut status, 0);
            if !libc::WIFEXITED(status) {
                anyhow::bail!("子进程异常退出");
            }
            match libc::WEXITSTATUS(status) {
                0 => Ok(Some(true)),
                1 => Ok(Some(false)), // GET 时为"关闭"
                c => anyhow::bail!("隐身 ioctl 失败（子进程退出码 {c}：10=切uid失败 11=没拿到新句柄 12=ioctl被拒）"),
            }
        } else {
            anyhow::bail!("fork 失败");
        }
    }
}

/// 管理器世代号（断代闸门）。
///
/// ⚠️ 必须与内核侧一致：`kernel/Kbuild` 的 `KSU_MANAGER_MIN_GEN`（= 2）以及
/// 管理器 APK 里的 `assets/ksu_manager_gen2` 条目、App JNI 的 `KSU_MANAGER_GEN`。
/// 内核只要求 >= 1（安全阀比闸门宽松），但三处保持一致最好维护。
pub const KSU_MANAGER_GEN: u32 = 2;

/// 断代闸门的安全阀：用**新命令**直接读写隐身（内核权限 stealth_valve_allowed）。
///
/// 为什么 ksud 需要它：
///   · 老内核没有这条命令 → -ENOTTY，调用方自然回退到老路径（行为不变）；
///   · 新内核上如果管理器**没被认主**（比如设备上装的是老版本 APK），
///     `manager_appid()` 拿不到 / 老命令被 only_manager 拒 → 网页管理器
///     就再也没法开关隐身了；而 ksud 是 root，安全阀对 root 放行。
///   · 老版本管理器 APK 里没有这条命令（它的代码是编译死的），
///     所以"老 APK 关不掉隐身"这条验收不受影响。
fn stealth_ioctl_valve(enabled: Option<bool>) -> anyhow::Result<Option<bool>> {
    let mut cmd: ksu_uapi::ksu_stealth_gen_cmd = unsafe { std::mem::zeroed() };
    cmd.manager_gen = KSU_MANAGER_GEN;
    let op = enabled.map_or(ksu_uapi::KSU_IOCTL_STEALTH_GET_G, |v| {
        cmd.enabled = u8::from(v);
        ksu_uapi::KSU_IOCTL_STEALTH_SET_G
    });
    ksuctl(op, &raw mut cmd)?;
    Ok(Some(cmd.enabled != 0))
}

/// 以管理器身份读隐身状态（拿不到管理器身份时退回安全阀）
pub fn stealth_get_authed() -> anyhow::Result<bool> {
    if let Ok(Some(v)) = stealth_ioctl_as_manager(None) {
        return Ok(v);
    }
    Ok(stealth_ioctl_valve(None)?.unwrap_or(false))
}

/// 以管理器身份写隐身开关（拿不到管理器身份时退回安全阀）
pub fn stealth_set_authed(enabled: bool) -> anyhow::Result<()> {
    if stealth_ioctl_as_manager(Some(enabled)).is_ok() {
        return Ok(());
    }
    stealth_ioctl_valve(Some(enabled))?;
    Ok(())
}

/// 数据目录迁移门控用：**读**内核当前隐身开关（安全阀命令，只读、不落盘）。
///
/// 老内核（≤ v0.13.150）不认识命令号 27 → `-ENOTTY` → 这里返回 `Err`；
/// 调用方（`migrate::probe_datadir_with`）把"任何错误"都当成**拿不准** ⇒ **不迁移**。
///
/// # Errors
/// 拿不到内核句柄 / 命令不被支持 / 权限不足。
pub fn stealth_probe_get() -> anyhow::Result<bool> {
    Ok(stealth_ioctl_valve(None)?.unwrap_or(false))
}

/// 数据目录迁移门控用：**写**隐身开关（安全阀命令）。
///
/// 关键点：内核收到这条命令后一定会把它**自己那个数据目录**里的 `stealth` 文件
/// 同步重写一遍（见 `kernel/manager/stealth.c` 的 `ksu_stealth_set`），
/// 所以我们拿它当"内核到底在读写哪个目录"的笔迹探测 —— 探测时用**同值写回**，
/// 语义上等于空操作（新目录不存在时会顺手把正确的那份写出来，反而是好事）。
///
/// # Errors
/// 拿不到内核句柄 / 命令不被支持 / 权限不足 / 内核写盘失败。
pub fn stealth_probe_set(enabled: bool) -> anyhow::Result<()> {
    stealth_ioctl_valve(Some(enabled))?;
    Ok(())
}

// ══════════════════════════════════════════════════════════════════
// AppProfile（超级用户的"授权 / 撤销 / root 配置"）
//
// 权限同样是 only_manager（见 kernel/supercall/dispatch.c 的分发表），
// root 直接调会被 EPERM —— 所以复用上面那套「fork + setresuid(管理器uid)
// + 关旧句柄 + 重扫新句柄」的办法，只是这次要把内核回写的结构体带回来。
// ══════════════════════════════════════════════════════════════════

/// 入参结构体字节上限（app_profile 是 784 字节，留足余量）
const MANAGER_IOCTL_MAX: usize = 4096;

/// 以管理器身份跑一次 ioctl。
///
/// 返回 `Ok(Some(字节))` = 成功（内核回写后的结构体）；`Ok(None)` = 内核回 `-ENOENT`
/// （GET 时表示"这个 uid 还没有 profile"，是正常情况，不是错误）。
///
/// 子进程**不做任何堆分配**（多线程进程 fork 后的安全做法）：
/// 入参放栈上的定长数组，结果用管道回传。
/// 退出码：10=切 uid 失败 11=没拿到管理器句柄 12=被内核拒绝 20=-ENOENT 13=回传失败
fn ioctl_as_manager(req: u32, input: &[u8]) -> anyhow::Result<Option<Vec<u8>>> {
    anyhow::ensure!(input.len() <= MANAGER_IOCTL_MAX, "入参结构体太大");
    let appid = manager_appid()?;
    if appid == u32::MAX || appid == 0 {
        anyhow::bail!("拿不到管理器 appid");
    }
    let inherited = DRIVER_FD.get().copied().unwrap_or(-1);
    let len = input.len();

    let mut fds = [0i32; 2];
    unsafe {
        if libc::pipe(fds.as_mut_ptr()) != 0 {
            anyhow::bail!("pipe 失败: {}", io::Error::last_os_error());
        }
    }
    let [rd, wr] = fds;
    let mut out = vec![0u8; len];

    unsafe {
        let pid = libc::fork();
        if pid == 0 {
            // ── 子进程 ──
            libc::close(rd);
            let mut buf = [0u8; MANAGER_IOCTL_MAX];
            std::ptr::copy_nonoverlapping(input.as_ptr(), buf.as_mut_ptr(), len);
            if libc::setresuid(appid, appid, appid) != 0 {
                libc::_exit(10);
            }
            // 关掉继承来的旧句柄，免得扫到它（它没有管理器权限位）
            if inherited >= 0 {
                libc::close(inherited);
            }
            // setresuid 那一刻内核给我们装的新句柄
            let Ok(Some(fd)) = scan_driver_fd() else {
                libc::_exit(11);
            };
            let ret = libc::ioctl(fd, req as libc::c_int, buf.as_mut_ptr());
            if ret != 0 {
                let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                libc::_exit(if errno == libc::ENOENT { 20 } else { 12 });
            }
            let mut off = 0usize;
            while off < len {
                let n = libc::write(wr, buf.as_ptr().add(off).cast::<libc::c_void>(), len - off);
                if n <= 0 {
                    libc::_exit(13);
                }
                off += n as usize;
            }
            libc::close(wr);
            libc::_exit(0);
        } else if pid < 0 {
            libc::close(rd);
            libc::close(wr);
            anyhow::bail!("fork 失败: {}", io::Error::last_os_error());
        }

        libc::close(wr);
        // 入参很小（远小于 64KB 管道容量），父进程随后再读不会死锁
        let mut off = 0usize;
        while off < len {
            let n = libc::read(rd, out.as_mut_ptr().add(off).cast::<libc::c_void>(), len - off);
            if n <= 0 {
                break;
            }
            off += n as usize;
        }
        libc::close(rd);
        let mut status: libc::c_int = 0;
        libc::waitpid(pid, &raw mut status, 0);
        if !libc::WIFEXITED(status) {
            anyhow::bail!("管理器身份子进程异常退出");
        }
        match libc::WEXITSTATUS(status) {
            0 => {}
            20 => return Ok(None),
            c => anyhow::bail!(
                "ioctl 失败（子进程退出码 {c}: 10=切uid失败 11=没拿到管理器句柄 12=被内核拒绝 13=回传失败）"
            ),
        }
        anyhow::ensure!(off == len, "ioctl 结果回传不完整（{off}/{len}）");
    }
    Ok(Some(out))
}

const fn struct_bytes<T>(v: &T) -> &[u8] {
    let p = std::ptr::from_ref(v).cast::<u8>();
    unsafe { std::slice::from_raw_parts(p, std::mem::size_of::<T>()) }
}

fn struct_from_bytes<T: Copy>(b: &[u8]) -> anyhow::Result<T> {
    anyhow::ensure!(b.len() >= std::mem::size_of::<T>(), "结构体字节数不足");
    // 这些都是 C 的 POD 结构（全是整数 / 定长字符数组 / bool），全零是合法初值
    let mut v: T = unsafe { std::mem::zeroed() };
    unsafe {
        std::ptr::copy_nonoverlapping(
            b.as_ptr(),
            std::ptr::addr_of_mut!(v).cast::<u8>(),
            std::mem::size_of::<T>(),
        );
    }
    Ok(v)
}

/// 把 Rust 字符串写进定长 `char[N]`（截断 + 结尾补 0）
pub fn write_cstr(buf: &mut [libc::c_char], s: &str) {
    let n = s.len().min(buf.len().saturating_sub(1));
    for (i, b) in s.as_bytes()[..n].iter().enumerate() {
        buf[i] = *b as libc::c_char;
    }
    if let Some(last) = buf.get_mut(n) {
        *last = 0;
    }
}

/// 读某个 App 的 AppProfile。
///
/// `Ok(None)` = 内核里没有这个 uid 的条目（= 没授权过、也没单独配过），
/// 此时界面按"默认值"展示（不给 root、非 root 用默认）。
pub fn get_app_profile(uid: u32, key: &str) -> anyhow::Result<Option<ksu_uapi::app_profile>> {
    let mut cmd: ksu_uapi::ksu_get_app_profile_cmd = unsafe { std::mem::zeroed() };
    cmd.profile.version = ksu_uapi::KSU_APP_PROFILE_VER;
    cmd.profile.curr_uid = uid as i32;
    write_cstr(&mut cmd.profile.key[..], key);
    match ioctl_as_manager(ksu_uapi::KSU_IOCTL_GET_APP_PROFILE, struct_bytes(&cmd))? {
        None => Ok(None),
        Some(out) => {
            let got: ksu_uapi::ksu_get_app_profile_cmd = struct_from_bytes(&out)?;
            Ok(Some(got.profile))
        }
    }
}

/// 写 AppProfile（授权 / 撤销 root、改 root 配置都走这里）
pub fn set_app_profile(profile: &ksu_uapi::app_profile) -> anyhow::Result<()> {
    let mut cmd: ksu_uapi::ksu_set_app_profile_cmd = unsafe { std::mem::zeroed() };
    cmd.profile = *profile;
    match ioctl_as_manager(ksu_uapi::KSU_IOCTL_SET_APP_PROFILE, struct_bytes(&cmd))? {
        Some(_) => Ok(()),
        None => anyhow::bail!("内核说没有这个 profile"),
    }
}

/// 从定长 `char[N]` 读回 Rust 字符串
#[allow(clippy::unnecessary_cast)] // 见下面那段注释：x86_64 上 c_char 是 i8，这个 cast 不能删
pub fn read_cstr(buf: &[libc::c_char]) -> String {
    let bytes: Vec<u8> = buf
        .iter()
        .take_while(|c| **c != 0)
        // ⚠️ `libc::c_char` 在 aarch64 上是 u8、在 x86_64 上是 **i8**。
        //    原来直接 `.copied().collect::<Vec<u8>>()`，于是 x86_64 target **编不过**
        //    （E0277: Vec<u8> cannot be built from i8）—— 本项目的 x86_64 ksud 一直是
        //    上游编译的旧产物。2026-09-20「彻底切割」需要重编 x86_64 那份（否则
        //    APK 里的 x86_64 libksud.so 还带着旧包名/旧路径），顺手把这一行修好：
        //    `as u8` 在 aarch64 上是恒等转换，不影响 arm64 行为。
        .map(|c| *c as u8)
        .collect();
    String::from_utf8_lossy(&bytes).to_string()
}
