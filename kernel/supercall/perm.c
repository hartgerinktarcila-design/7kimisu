#include <linux/types.h>

#include "supercall/internal.h"
#include "manager/manager_identity.h"
#include "manager/app_lock.h"
#include "policy/allowlist.h"

bool only_manager(void)
{
    return is_manager();
}

bool only_root(void)
{
    return current_uid().val == 0;
}

bool manager_or_root(void)
{
    return current_uid().val == 0 || is_manager();
}

bool always_allow(void)
{
    return true;
}

bool allowed_for_su(void)
{
    return is_manager() || ksu_is_allow_uid_for_current(current_uid().val);
}

/*
 * Variants that additionally require the app lock to be unlocked.
 * Already-allowed uids and uid 0 keep working, so the boot flow and existing
 * root users are not affected by the lock.
 */
bool only_manager_authed(void)
{
    return is_manager() && ksu_app_lock_ok();
}

bool manager_or_root_authed(void)
{
    return current_uid().val == 0 || only_manager_authed();
}

bool allowed_for_su_authed(void)
{
    if (current_uid().val == 0) {
        return true;
    }

    if (ksu_is_allow_uid_for_current(current_uid().val)) {
        return true;
    }

    return only_manager_authed();
}

/*
 * 🔴🔴 断代闸门的安全阀（后门）—— 这是「绝不把自己锁在外面」的那一行。
 *
 * KSU_IOCTL_STEALTH_GET_G / KSU_IOCTL_STEALTH_SET_G 这两条**新命令**用它当权限。
 * 老版本管理器 APK 里根本没有这两条命令（它们的代码是编译死的），
 * 只会走老的 KSU_IOCTL_STEALTH_GET/SET（权限仍是 only_manager）→ -EPERM，
 * 所以"老 APK 关不掉隐身"；而**我们自己签名的管理器**（任意一代、哪怕内核
 * 因为闸门误判/重扫时序而没认主）随时能关隐身，root 也能（救砖用）。
 */
bool stealth_valve_allowed(void)
{
    if (current_uid().val == 0) {
        return true;
    }

    if (is_manager()) {
        return true;
    }

    return ksu_is_signed_manager_uid(current_uid().val);
}
