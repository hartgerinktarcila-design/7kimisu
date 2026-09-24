#ifndef __KSU_UAPI_SUPERCALL_H
#define __KSU_UAPI_SUPERCALL_H

#include <linux/ioctl.h>
#include <linux/types.h>

#include "uapi/app_profile.h"

// 2: allowlist v4 root profile flags
// 3: scoped su-session driver fd
// 4: add KSU_GET_INFO_FLAG_BUNDLED
// 5: add stealth get/set
static const __u32 KERNEL_SU_UAPI_VERSION = 5;

/* Magic numbers for reboot hook to install fd */
static const __u32 KSU_INSTALL_MAGIC1 = 0xDEADBEEF;
static const __u32 KSU_INSTALL_MAGIC2 = 0xCAFEBABE;

struct ksu_become_daemon_cmd {
    __u8 token[65]; /* Input: daemon token (null-terminated) */
};

static const __u32 EVENT_POST_FS_DATA = 1;
static const __u32 EVENT_BOOT_COMPLETED = 2;
static const __u32 EVENT_MODULE_MOUNTED = 3;

static const __u32 KSU_GET_INFO_FLAG_LKM = (1U << 0);
static const __u32 KSU_GET_INFO_FLAG_MANAGER = (1U << 1);
static const __u32 KSU_GET_INFO_FLAG_LATE_LOAD = (1U << 2);
static const __u32 KSU_GET_INFO_FLAG_PR_BUILD = (1U << 3);
static const __u32 KSU_GET_INFO_FLAG_BUNDLED = (1U << 4);

struct ksu_get_info_cmd {
    __u32 version; /* Output: KERNEL_SU_VERSION */
    __u32 flags; /* Output: KSU_GET_INFO_FLAG_* bits */
    __u32 features; /* Output: max feature ID supported */
    __u32 uapi_version; /* Output: KERNEL_SU_UAPI_VERSION */
};

struct ksu_get_info_legacy_cmd {
    __u32 version; /* Output: KERNEL_SU_VERSION */
    __u32 flags; /* Output: KSU_GET_INFO_FLAG_* bits */
    __u32 features; /* Output: max feature ID supported */
};

struct ksu_report_event_cmd {
    __u32 event; /* Input: EVENT_POST_FS_DATA, EVENT_BOOT_COMPLETED, etc. */
};

struct ksu_set_sepolicy_cmd {
    __u64 data_len; /* Input: bytes of serialized command payload */
    __aligned_u64 data; /* Input: pointer to serialized payload */
};

struct ksu_sepolicy_cmd_hdr {
    __u32 cmd; /* Input: command type, CMD_* */
    __u32 subcmd; /* Input: command subtype */
};
/*
 * After each ksu_sepolicy_cmd_hdr, command arguments are encoded sequentially as:
 * [u32 len][len bytes][\0], where len excludes the trailing '\0'.
 * len == 0 represents ALL.
 * Argument count is derived from cmd:
 * KSU_SEPOLICY_CMD_NORMAL_PERM=4, KSU_SEPOLICY_CMD_XPERM=5,
 * KSU_SEPOLICY_CMD_TYPE_STATE=1, KSU_SEPOLICY_CMD_TYPE=2,
 * KSU_SEPOLICY_CMD_TYPE_ATTR=2, KSU_SEPOLICY_CMD_ATTR=1,
 * KSU_SEPOLICY_CMD_TYPE_TRANSITION=5, KSU_SEPOLICY_CMD_TYPE_CHANGE=4,
 * KSU_SEPOLICY_CMD_GENFSCON=3.
 */

struct ksu_check_safemode_cmd {
    __u8 in_safe_mode; /* Output: true if in safe mode, false otherwise */
};

/* deprecated */
struct ksu_get_allow_list_cmd {
    __u32 uids[128]; /* Output: array of allowed/denied UIDs */
    __u32 count; /* Output: number of UIDs in array */
    __u8 allow; /* Input: true for allow list, false for deny list */
};

struct ksu_new_get_allow_list_cmd {
    __u16 count; /* Input / Output: number of UIDs in array */
    __u16 total_count; /* Output: total number of UIDs in requested list */
    __u32 uids[0]; /* Output: array of allowed/denied UIDs */
};

struct ksu_uid_granted_root_cmd {
    __u32 uid; /* Input: target UID to check */
    __u8 granted; /* Output: true if granted, false otherwise */
};

struct ksu_uid_should_umount_cmd {
    __u32 uid; /* Input: target UID to check */
    __u8 should_umount; /* Output: true if should umount, false otherwise */
};

struct ksu_get_manager_appid_cmd {
    __u32 appid; /* Output: manager app id */
};

struct ksu_get_app_profile_cmd {
    struct app_profile profile; /* Input/Output: app profile structure */
};

struct ksu_set_app_profile_cmd {
    struct app_profile profile; /* Input: app profile structure */
};

struct ksu_get_feature_cmd {
    __u32 feature_id; /* Input: feature ID (enum ksu_feature_id) */
    __u64 value; /* Output: feature value/state */
    __u8 supported; /* Output: true if feature is supported, false otherwise */
};

struct ksu_set_feature_cmd {
    __u32 feature_id; /* Input: feature ID (enum ksu_feature_id) */
    __u64 value; /* Input: feature value/state to set */
};

struct ksu_get_wrapper_fd_cmd {
    __u32 fd; /* Input: userspace fd */
    __u32 flags; /* Input: flags of userspace fd */
};

struct ksu_manage_mark_cmd {
    __u32 operation; /* Input: KSU_MARK_* */
    __s32 pid; /* Input: target pid (0 for all processes) */
    __u32 result; /* Output: for get operation - mark status or reg_count */
};

static const __u32 KSU_MARK_GET = 1;
static const __u32 KSU_MARK_MARK = 2;
static const __u32 KSU_MARK_UNMARK = 3;
static const __u32 KSU_MARK_REFRESH = 4;

struct ksu_nuke_ext4_sysfs_cmd {
    __aligned_u64 arg; /* Input: mnt pointer */
};

struct ksu_add_try_umount_cmd {
    __aligned_u64 arg; /* char ptr, this is the mountpoint */
    __u32 flags; /* this is the flag we use for it */
    __u8 mode; /* denotes what to do with it 0:wipe_list 1:add_to_list 2:delete_entry */
};

struct ksu_get_sulog_fd_cmd {
    __u32 flags; /* Input: reserved for future use, must be 0 */
};

static const __u8 KSU_UMOUNT_WIPE = 0; /* ignore everything and wipe list */
static const __u8 KSU_UMOUNT_ADD = 1; /* add entry (path + flags) */
static const __u8 KSU_UMOUNT_DEL = 2; /* delete entry, strcmp */

/* IOCTL command definitions */
static const __u32 KSU_IOCTL_GRANT_ROOT = _IOC(_IOC_NONE, 'K', 1, 0);
static const __u32 KSU_IOCTL_GET_INFO = _IOR('K', 2, struct ksu_get_info_cmd);
/* deprecated */
static const __u32 KSU_IOCTL_GET_INFO_LEGACY = _IOC(_IOC_READ, 'K', 2, 0);
static const __u32 KSU_IOCTL_REPORT_EVENT = _IOC(_IOC_WRITE, 'K', 3, 0);
static const __u32 KSU_IOCTL_SET_SEPOLICY = _IOC(_IOC_READ | _IOC_WRITE, 'K', 4, 0);
static const __u32 KSU_IOCTL_CHECK_SAFEMODE = _IOC(_IOC_READ, 'K', 5, 0);
/* deprecated */
static const __u32 KSU_IOCTL_GET_ALLOW_LIST = _IOC(_IOC_READ | _IOC_WRITE, 'K', 6, 0);
/* deprecated */
static const __u32 KSU_IOCTL_GET_DENY_LIST = _IOC(_IOC_READ | _IOC_WRITE, 'K', 7, 0);
static const __u32 KSU_IOCTL_NEW_GET_ALLOW_LIST = _IOWR('K', 6, struct ksu_new_get_allow_list_cmd);
static const __u32 KSU_IOCTL_NEW_GET_DENY_LIST = _IOWR('K', 7, struct ksu_new_get_allow_list_cmd);
static const __u32 KSU_IOCTL_UID_GRANTED_ROOT = _IOC(_IOC_READ | _IOC_WRITE, 'K', 8, 0);
static const __u32 KSU_IOCTL_UID_SHOULD_UMOUNT = _IOC(_IOC_READ | _IOC_WRITE, 'K', 9, 0);
static const __u32 KSU_IOCTL_GET_MANAGER_APPID = _IOC(_IOC_READ, 'K', 10, 0);
static const __u32 KSU_IOCTL_GET_APP_PROFILE = _IOC(_IOC_READ | _IOC_WRITE, 'K', 11, 0);
static const __u32 KSU_IOCTL_SET_APP_PROFILE = _IOC(_IOC_WRITE, 'K', 12, 0);
static const __u32 KSU_IOCTL_GET_FEATURE = _IOC(_IOC_READ | _IOC_WRITE, 'K', 13, 0);
static const __u32 KSU_IOCTL_SET_FEATURE = _IOC(_IOC_WRITE, 'K', 14, 0);
static const __u32 KSU_IOCTL_GET_WRAPPER_FD = _IOC(_IOC_WRITE, 'K', 15, 0);
static const __u32 KSU_IOCTL_MANAGE_MARK = _IOC(_IOC_READ | _IOC_WRITE, 'K', 16, 0);
static const __u32 KSU_IOCTL_NUKE_EXT4_SYSFS = _IOC(_IOC_WRITE, 'K', 17, 0);
static const __u32 KSU_IOCTL_ADD_TRY_UMOUNT = _IOC(_IOC_WRITE, 'K', 18, 0);
static const __u32 KSU_IOCTL_SET_INIT_PGRP = _IO('K', 19);
static const __u32 KSU_IOCTL_GET_SULOG_FD = _IOW('K', 20, struct ksu_get_sulog_fd_cmd);
static const __u32 KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT = _IO('K', 21);


/* App lock (superkey): kernel-enforced password gate for privileged operations */
struct ksu_app_lock_state_cmd {
    __u8 configured; /* Output: whether a lock is configured */
    __u8 authed; /* Output: whether already authenticated this boot */
};

struct ksu_app_lock_auth_cmd {
    __u8 hash[32]; /* Input: SHA-256(password) */
};

struct ksu_app_lock_set_cmd {
    __u8 hash[32]; /* Input: new SHA-256(password) */
    __u8 disable; /* Input: 1 to disable the lock */
    __u8 state; /* Output: 1 if a lock is configured after the operation */
};

static const __u32 KSU_IOCTL_APP_LOCK_STATE = _IOR('K', 22, struct ksu_app_lock_state_cmd);
static const __u32 KSU_IOCTL_APP_LOCK_AUTH = _IOW('K', 23, struct ksu_app_lock_auth_cmd);
static const __u32 KSU_IOCTL_APP_LOCK_SET = _IOWR('K', 24, struct ksu_app_lock_set_cmd);

/*
 * 隐身模式:开启后 GET_INFO 不再上报 MANAGER 标志,管理器整个界面伪装成"未安装"。
 * 内核真实权限不变,因此只有真正的管理器(签名匹配)能读写该开关。
 */
struct ksu_stealth_cmd {
    __u8 enabled; /* Input for SET, Output for GET */
};

static const __u32 KSU_IOCTL_STEALTH_GET = _IOR('K', 25, struct ksu_stealth_cmd);
static const __u32 KSU_IOCTL_STEALTH_SET = _IOW('K', 26, struct ksu_stealth_cmd);

/*
 * 断代闸门（2026-09-20）
 *
 *   认主是内核扫包**自动**完成的（kernel/manager/throne_tracker.c）：
 *   内核在 /data/app 里扫到「签名匹配」的 base.apk 就把它设成管理器。
 *   闸门加在**那一步**：除签名外，APK 还必须带 `assets/ksu_manager_gen<N>`
 *   条目（N >= kernel/Kbuild 的 KSU_MANAGER_MIN_GEN）。老版本 APK 的包里
 *   没有这个条目，物理上满足不了 → 永远不会被认主。
 *
 *   老 APK 同样**不认识下面这两条新命令**，所以它连"关隐身"都做不到
 *   （STEALTH_GET/SET 的权限仍是 only_manager，它没被认主 → -EPERM）；
 *   而**新版管理器**即使因为别的原因没被认主，也能用新命令关掉隐身。
 *
 * ⚠️ 这里**故意不动 KERNEL_SU_UAPI_VERSION**（仍是 5）：
 *   ksud 的 ensure_uapi_version_matched() 要求 kernel_uapi >= ksud_uapi，
 *   一旦把常量抬到 6，"新 APK + 老内核"会被判 "UAPI too old" → 模块挂载 /
 *   开机流程全废（违反「老内核 + 任意版本行为不变」）。
 *   新增 ioctl 天生向后兼容（老内核对未知 cmd 返回 -ENOTTY），不需要抬版本号。
 */
struct ksu_stealth_gen_cmd {
    __u32 manager_gen; /* Input: 管理器世代号; Output(GET): 内核要求的世代号下限 */
    __u8 enabled; /* Input for SET, Output for GET */
    __u8 _pad[3];
};

static const __u32 KSU_IOCTL_STEALTH_GET_G = _IOR('K', 27, struct ksu_stealth_gen_cmd);
static const __u32 KSU_IOCTL_STEALTH_SET_G = _IOW('K', 28, struct ksu_stealth_gen_cmd);

#endif
