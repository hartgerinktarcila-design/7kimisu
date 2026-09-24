// SPDX-License-Identifier: GPL-2.0
/*
 * 应用锁(超级密钥)已弃用。
 *
 * 用户决定不再需要"进入 App 输入密码",改为纯隐身模式(见 manager/stealth.c)。
 * 这里保留同名函数签名,以免改动 supercall 分发表与权限代码,
 * 但内核不再生成/校验任何密钥:锁永远是"未配置 + 已解锁"状态,
 * 因此 only_manager_authed() 等权限检查不会再拦截管理器。
 *
 * 之前写入的 /data/adb/sevenk/applock.hash 与 /sdcard/Download/7kkernel_key.txt
 * 会被忽略(不再读取),可以手动删除。
 */
#include <linux/types.h>
#include <linux/errno.h>

#include "manager/app_lock.h"
#include "klog.h" // IWYU pragma: keep

void ksu_app_lock_load(void)
{
    pr_info("app_lock: disabled (passwordless mode)\n");
}

void ksu_app_lock_reset_load(void)
{
}

bool ksu_app_lock_is_configured(void)
{
    return false;
}

bool ksu_app_lock_is_authed(void)
{
    return true;
}

bool ksu_app_lock_ok(void)
{
    return true;
}

int ksu_app_lock_auth(const u8 *hash)
{
    (void)hash;
    return 0;
}

int ksu_app_lock_set(const u8 *hash, bool disable)
{
    (void)hash;
    (void)disable;
    return -EPERM;
}
