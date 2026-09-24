#ifndef __KSU_H_MANAGER_IDENTITY
#define __KSU_H_MANAGER_IDENTITY

#include <linux/cred.h>
#include <linux/types.h>

#define KSU_INVALID_APPID -1
#define KSU_PER_USER_RANGE 100000

/*
 * 断代闸门：管理器 APK 必须在自己的包里声明世代号（`assets/ksu_manager_gen<N>`），
 * 且 N >= KSU_MANAGER_MIN_GEN 才会被认主（见 kernel/manager/apk_sign.c 的
 * ksu_manager_apk_gen() 与 throne_tracker.c 的 my_actor()）。
 *
 * 由 Kbuild 的 KSU_MANAGER_MIN_GEN 注入；2 = 2026-09-20 这一批「带世代标记」的
 * 管理器（v0.13.151+）。以后要"再断一代"就把这个号 +1，并同批发一版带
 * assets/ksu_manager_gen<N+1> 的 APK。
 *
 * 注意：安全阀（supercall/perm.c 的 stealth_valve_allowed）**故意不用**这个下限，
 * 只要调用者能证明自己是"会声明世代的新版管理器代码"就放行 —— 闸门本身万一
 * 误判，也必须留一条能关掉隐身的活路。
 */
#ifndef KSU_MANAGER_MIN_GEN
#define KSU_MANAGER_MIN_GEN 2
#endif

#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline bool ksu_is_manager_appid_valid()
{
    return true;
}

static inline bool is_manager()
{
    return current_uid().val == 0;
}

static inline bool is_uid_manager(uid_t uid)
{
    return uid == 0;
}

static inline bool ksu_is_signed_manager_uid(uid_t uid)
{
    (void)uid;
    return false;
}

static inline uid_t ksu_get_manager_appid()
{
    return 0;
}

static inline void ksu_set_manager_appid(uid_t appid)
{
    (void)appid;
}

static inline void ksu_set_signed_manager_appid(uid_t appid)
{
    (void)appid;
}

static inline void ksu_invalidate_manager_uid()
{
}
#else
extern uid_t ksu_manager_appid; // DO NOT DIRECT USE

/*
 * 「签名对、但没过世代闸门（或还没被认主）」的管理器 appid。
 *
 * 只给安全阀用：调用者 uid 命中它 => 这是一份我们自己签名的管理器 APK，
 * 允许它用新命令（KSU_IOCTL_STEALTH_*_G）读写隐身开关，**与是否被认主无关**。
 * 老版本 APK 虽然也会命中它，但老 APK 的代码里根本没有那两条新命令，
 * 它只能走老的 KSU_IOCTL_STEALTH_SET/GET（权限 only_manager）→ 被拒。
 */
extern uid_t ksu_signed_manager_appid; // DO NOT DIRECT USE

static inline bool ksu_is_manager_appid_valid()
{
    return ksu_manager_appid != KSU_INVALID_APPID;
}

static inline bool is_manager()
{
    return unlikely(ksu_manager_appid == current_uid().val % KSU_PER_USER_RANGE);
}

static inline bool is_uid_manager(uid_t uid)
{
    return unlikely(ksu_manager_appid == uid % KSU_PER_USER_RANGE);
}

static inline bool ksu_is_signed_manager_uid(uid_t uid)
{
    return unlikely(ksu_signed_manager_appid != KSU_INVALID_APPID &&
                    ksu_signed_manager_appid == uid % KSU_PER_USER_RANGE);
}

static inline uid_t ksu_get_manager_appid()
{
    return ksu_manager_appid;
}

static inline void ksu_set_manager_appid(uid_t appid)
{
    ksu_manager_appid = appid;
}

static inline void ksu_set_signed_manager_appid(uid_t appid)
{
    ksu_signed_manager_appid = appid;
}

static inline void ksu_invalidate_manager_uid()
{
    ksu_manager_appid = KSU_INVALID_APPID;
}
#endif

#endif // __KSU_H_MANAGER_IDENTITY
