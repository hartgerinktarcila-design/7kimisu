//
// Created by weishu on 2022/12/9.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <climits>
#include <sys/syscall.h>
#include <cerrno>
#include <ctime>   // clock_gettime / CLOCK_MONOTONIC（节流用）
#include "ksu.h"

static int fd = -1;

/**
 * 「驱动 fd 发现」的节流状态（2026-09-19，G1 性能修复）。
 *
 * 背景：install_driver_fd() 默认关闭（KSU_ENABLE_INSTALL_DRIVER_FD = 0，原因见下），
 * 于是「本进程有没有 [ksu_driver] 这个 fd」**只能**靠 scan_driver_fd() 翻
 * /proc/self/fd 来发现。而 fd 是内核在 setresuid 那一刻一次性装进来的，
 * 完全可能在 App 启动**之后**才出现（刚更新过 APK → appid 被作废 → 之后才被
 * track_throne 重新认出），所以**不能**把「没有 fd」这个结论永久缓存 ——
 * 一缓存就永远显示【未安装】。
 *
 * 但也不能每次 JNI 调用都翻一遍 /proc/self/fd：opendir + readdir + 每个 fd 一次
 * readlink，一个进程几十上百个 fd 就是几百个系统调用；首页一次刷新会调十几次
 * Natives.*，全在主线程上，是实打实的卡顿。
 *
 * 折中：**最多每 FD_SCAN_MIN_INTERVAL_MS 毫秒重扫一次**，其余调用沿用上次的结论。
 *   · 「后来才出现」的 fd → 最迟 2 秒后就能被发现（语义保留，不是永久缓存）；
 *   · 稳态（已拿到 fd，fd >= 0）压根不走扫描这条路，零额外开销；
 *   · 没装内核的机器 → 每 2 秒才扫一次，不再每次调用都扫。
 */
static constexpr int64_t FD_SCAN_MIN_INTERVAL_MS = 2000;

/** 上次扫 /proc/self/fd 的单调时刻（毫秒）；< 0 表示本进程还从没扫过 */
static int64_t last_fd_scan_ms = -1;

/**
 * 单调时钟（毫秒）。失败返回 -1 —— 调用方据此退回「每次都扫」的旧行为，
 * 绝不因为拿不到时间就把「发现新 fd」这件事永久堵死。
 */
static inline int64_t monotonic_now_ms() {
    struct timespec ts {};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return -1;
    }
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

static struct ksu_get_info_cmd g_version {};

/*
 * 主动向内核申请驱动 fd（与 ksud 用的是同一个入口）。
 * 🔴 注意：本函数**默认关闭**（KSU_ENABLE_INSTALL_DRIVER_FD = 0），原因见下方长注释。
 *
 * 为什么需要这一步：
 *   驱动 fd 是内核在「进程 setresuid 成管理器 uid」那一刻一次性装进来的
 *   （kernel/hook/setuid_hook.c: is_uid_manager(new_uid) → ksu_install_fd()）。
 *   如果那一刻内核还没认出管理器（典型场景：刚更新过 APK，appid 被作废、
 *   还没被 track_throne 重新扫到），**这个进程就永远拿不到 fd** ——
 *   表现就是 Natives.isManager 一直 false、模块/超级用户页空白、
 *   网页管理器显示"未识别到 root"，只有重开 App（新进程）才好。
 *
 *   ksud 早就会用这个 reboot 魔数自救（userspace/ksud/src/ksucalls.rs
 *   的 init_driver_fd），App 侧一直缺这一步 —— 这里补上。
 *
 * 安全性：魔数不对时内核的 kprobe 不会排队安装 fd，真正的 reboot 系统调用
 *   会因为 cmd 非法返回 EINVAL，**不会真的重启设备**。
 *
 * 🔴🔴 但这条自救**默认必须关掉**（见下面 KSU_ENABLE_INSTALL_DRIVER_FD = 0）🔴🔴
 *   原因（2026-09-19，粉丝真机崩溃日志实锤）：
 *     机型 Redmi xaga / Android 13 / MIUI V14.0.7.0 上，App 进程的 seccomp 策略
 *     不允许 reboot(2)：这句裸 `syscall(__NR_reboot, ...)` 会被内核**直接变成
 *     SIGSYS 并当场杀掉整个进程**，native 崩溃栈是
 *       #00 pc …/libc.so (syscall+32)
 *       #01 pc …/libsevenk.so            ← 就是下面这句
 *     表现就是"一打开/一刷新就闪退"，而且和版本号无关。
 *   （作者的机器之所以"看起来没事"，只是因为那儿 seccomp 放行了、只返回 EPERM；
 *     ★ 部分机型才崩，所以很容易漏掉 —— 有真机日志为证，别手贱打开。）
 *
 *   ⚠️ 关掉它**不会**少任何功能：找不到驱动 fd 时照旧走"没有内核访问"这条路，
 *     App 显示【未安装】，而**不是进程被杀** —— 这正是本次修复的目标：
 *     把"闪退"降级成"显示未安装"。要恢复旧行为需显式改编译开关
 *     （CMake 传 -DKSU_ENABLE_INSTALL_DRIVER_FD=1），默认永远关。
 */
#define KSU_INSTALL_MAGIC1 0xDEADBEEF
#define KSU_INSTALL_MAGIC2 0xCAFEBABE

/**
 * 编译期开关：是否允许用 reboot(2) 魔数自救。
 *
 * ⚠️ 默认 0（关闭）—— 打开会让 MIUI 等开启了 seccomp 的 ROM 上的 App
 *    因 SIGSYS 被直接杀掉（真机日志见上面的说明）。除特殊调试外不要打开。
 */
#ifndef KSU_ENABLE_INSTALL_DRIVER_FD
#define KSU_ENABLE_INSTALL_DRIVER_FD 0
#endif

static inline int install_driver_fd() {
#if KSU_ENABLE_INSTALL_DRIVER_FD
    int new_fd = -1;
    syscall(__NR_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &new_fd);
    if (new_fd < 0) {
        return -1;
    }
    return new_fd;
#else
    // 默认走这里：**不发起任何系统调用**，直接报"没有 fd"。
    // 调用方（ksuctl）会照旧走"没有内核访问"的分支 → App 显示【未安装】，
    // 绝不会因为 seccomp/SIGSYS 把进程带走。
    return -1;
#endif
}

static inline int scan_driver_fd() {
    const char *kName = "[ksu_driver]";
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) {
        return -1;
    }

    int found = -1;
    struct dirent *de;
    char path[64];
    char target[PATH_MAX];

    while ((de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.') {
            continue;
        }

        char *endptr = NULL;
        long fd_long = strtol(de->d_name, &endptr, 10);
        if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
            continue;
        }

        snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) {
            continue;
        }
        target[n] = '\0';

        const char *base = strrchr(target, '/');
        base = base ? base + 1 : target;

        if (strstr(base, kName)) {
            found = (int)fd_long;
            break;
        }
    }

    closedir(dir);
    return found;
}

/**
 * 尽力"发现"驱动 fd 一次，受上面的节流窗口限制。
 *
 * fd >= 0 直接返回；否则在允许的时间窗内扫一次 /proc/self/fd，
 * 没找到再按老逻辑尝试主动申请（默认编译开关下是空操作，返回 -1）。
 */
static inline void ensure_driver_fd() {
    if (fd >= 0) {
        return;
    }

    const int64_t now = monotonic_now_ms();
    // now < 0（取时间失败）→ 不做节流，退回旧行为；这样"发现新 fd"永远不会被永久堵死
    if (now >= 0 && last_fd_scan_ms >= 0 && now - last_fd_scan_ms < FD_SCAN_MIN_INTERVAL_MS) {
        // 刚扫过、还是没有 —— 这一轮直接用"没有 fd"的结论，不再翻 /proc
        return;
    }
    last_fd_scan_ms = now;

    fd = scan_driver_fd();
    if (fd >= 0) {
        return;
    }

    // 本进程没有 fd（启动那一刻内核还没认出管理器）—— 主动申请一次
    fd = install_driver_fd();
    if (fd >= 0) {
        // 拿到 fd 说明现在能通了，把之前可能缓存的"不是管理器"结论作废
        g_version = {};
        __android_log_print(ANDROID_LOG_INFO, "7kkernel-ksu",
                            "late install driver fd ok: %d", fd);
    } else {
        __android_log_print(ANDROID_LOG_WARN, "7kkernel-ksu",
                            "no driver fd and late install failed (errno=%d)", errno);
    }
}

template<typename... Args>
static int ksuctl(unsigned long op, Args &&... args) {

    ensure_driver_fd();

    static_assert(sizeof...(Args) <= 1, "ioctl expects at most one extra argument");

    return ioctl(fd, op, std::forward<Args>(args)...);
}

struct ksu_get_info_cmd get_info() {
    /*
     * 两边都别缓存"坏结论"：
     *   · version == 0        → 上一次根本没读成功，必须重试
     *   · MANAGER 标志为 0    → 可能是【隐身开着】或【appid 刚被作废还没重扫】，
     *     这两种都会自己恢复，缓存住就等于界面一直"未安装"。
     * 正常情况下（已认出、没隐身）才复用缓存，避免每次都 ioctl。
     */
    if (!g_version.version || !(g_version.flags & KSU_GET_INFO_FLAG_MANAGER)) {
        if (ksuctl(KSU_IOCTL_GET_INFO, &g_version) < 0) {
            ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &g_version);
            g_version.uapi_version = 0;
        }
    }
    return g_version;
}

uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

bool is_safe_mode() {
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return (legacy_get_info().second & KSU_GET_INFO_FLAG_LKM) != 0;
}

bool is_lkm_bundled() {
    auto info = get_info();
    return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0 &&
           (info.flags & KSU_GET_INFO_FLAG_BUNDLED) != 0;
}

bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0;
    }
    return legacy_get_info().first > 0;
}

bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!cmd.supported) {
        return false;
    }
    return cmd.value != 0;
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported;
    return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

bool app_lock_state(uint8_t *configured, uint8_t *authed) {
    struct ksu_app_lock_state_cmd cmd{};
    if (ksuctl(KSU_IOCTL_APP_LOCK_STATE, &cmd) != 0) {
        return false;
    }
    if (configured != nullptr) {
        *configured = cmd.configured;
    }
    if (authed != nullptr) {
        *authed = cmd.authed;
    }
    return true;
}

bool app_lock_auth(const uint8_t *hash) {
    struct ksu_app_lock_auth_cmd cmd{};
    memcpy(cmd.hash, hash, sizeof(cmd.hash));
    return ksuctl(KSU_IOCTL_APP_LOCK_AUTH, &cmd) == 0;
}

bool app_lock_set(const uint8_t *hash, bool disable, uint8_t *configured) {
    struct ksu_app_lock_set_cmd cmd{};
    memcpy(cmd.hash, hash, sizeof(cmd.hash));
    cmd.disable = disable ? 1 : 0;
    if (ksuctl(KSU_IOCTL_APP_LOCK_SET, &cmd) != 0) {
        return false;
    }
    if (configured != nullptr) {
        *configured = cmd.state;
    }
    return true;
}

/*
 * 管理器世代号（断代闸门）。
 *
 * ⚠️ 三处必须一致：
 *   ① 这里（App JNI 发出去的世代号）
 *   ② APK 包里的标记条目 `assets/ksu_manager_gen2`（内核认主时读它，
 *      见 kernel/manager/apk_sign.c 的 ksu_manager_apk_gen）
 *   ③ 内核 kernel/Kbuild 的 KSU_MANAGER_MIN_GEN（= 2）
 * 改世代号 = 「再断一代」：三处一起 +1，并同批发布。
 */
#define KSU_MANAGER_GEN 2

bool stealth_get(bool *enabled) {
    struct ksu_stealth_cmd cmd{};
    if (ksuctl(KSU_IOCTL_STEALTH_GET, &cmd) == 0) {
        if (enabled != nullptr) {
            *enabled = cmd.enabled != 0;
        }
        return true;
    }

    /*
     * 断代闸门的安全阀：新内核上如果本 App 还没被认主（重扫时序 / 刚装完还没重启），
     * 老命令会被 only_manager 拒。此时改用带世代号的新命令 —— 老版本 APK 的代码里
     * 根本没有这两条命令，所以它们照样读不到、也关不掉隐身。
     * 老内核对未知命令回 -ENOTTY，这里失败后仍然返回 false（老内核行为不变）。
     */
    struct ksu_stealth_gen_cmd gen_cmd{};
    gen_cmd.manager_gen = KSU_MANAGER_GEN;
    if (ksuctl(KSU_IOCTL_STEALTH_GET_G, &gen_cmd) != 0) {
        return false;
    }
    if (enabled != nullptr) {
        *enabled = gen_cmd.enabled != 0;
    }
    return true;
}

bool stealth_set(bool enabled) {
    struct ksu_stealth_cmd cmd{};
    cmd.enabled = enabled ? 1 : 0;
    if (ksuctl(KSU_IOCTL_STEALTH_SET, &cmd) != 0) {
        /* 同上：没被认主时走安全阀命令（老 APK 做不到这一步） */
        struct ksu_stealth_gen_cmd gen_cmd{};
        gen_cmd.manager_gen = KSU_MANAGER_GEN;
        gen_cmd.enabled = enabled ? 1 : 0;
        if (ksuctl(KSU_IOCTL_STEALTH_SET_G, &gen_cmd) != 0) {
            return false;
        }
    }
    /* get_info() 的结果被缓存在 g_version 里,切换隐身状态后必须让它
     * 重新从内核读取 flags,否则 Natives.isManager 不会立刻变化。 */
    g_version = {};
    return true;
}
