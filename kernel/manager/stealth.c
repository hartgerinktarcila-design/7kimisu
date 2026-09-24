// SPDX-License-Identifier: GPL-2.0
/*
 * 隐身模式(stealth)
 *
 *   开启后,内核在 GET_INFO 时不再向管理器 App 上报 KSU_GET_INFO_FLAG_MANAGER,
 *   于是 App 认为 is_manager == false,整个界面伪装成"未安装":
 *   首页状态卡变成"未安装/点击安装",超级用户/模块页无数据,
 *   设置里的 root 相关项全部消失(由 KsuIsValid 驱动)。
 *
 *   注意:这里只影响"上报给 App 的 flag",不影响内核真正的权限判定
 *   (is_manager() 依然为真),所以管理器仍然可以读/写这个开关 ——
 *   这正是"点 5 下内核版本恢复"能够工作的原因。
 *
 *   状态持久化在 /data/adb/sevenk/stealth,内容为 '0' / '1'。
 *   卸载并重装管理器 App 后状态依然保留(POST_FS_DATA 时重新加载)。
 */
#include <linux/types.h>
#include <linux/err.h>
#include <linux/errno.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/fcntl.h>
#include <linux/cred.h>

#include "manager/stealth.h"
#include "ksu.h"
#include "klog.h" // IWYU pragma: keep

#define KSU_STEALTH_PATH "/data/adb/sevenk/stealth"

static bool ksu_stealth_enabled;
static bool ksu_stealth_loaded;

static int ksu_stealth_write(const char *data, size_t len)
{
    struct file *fp;
    const struct cred *saved;
    loff_t off = 0;

    saved = override_creds(ksu_cred);
    fp = filp_open(KSU_STEALTH_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (IS_ERR(fp)) {
        revert_creds(saved);
        return PTR_ERR(fp);
    }

    if (kernel_write(fp, data, len, &off) != (ssize_t)len)
        pr_warn("stealth: short write to %s\n", KSU_STEALTH_PATH);

    filp_close(fp, NULL);
    revert_creds(saved);
    return 0;
}

void ksu_stealth_load(void)
{
    struct file *fp;
    char c = '0';
    loff_t pos = 0;

    ksu_stealth_loaded = true;

    fp = filp_open(KSU_STEALTH_PATH, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        /* 文件不存在 = 默认关闭 */
        ksu_stealth_enabled = false;
        return;
    }

    ksu_stealth_enabled = (kernel_read(fp, &c, 1, &pos) == 1 && c == '1');
    filp_close(fp, NULL);

    pr_info("stealth: loaded, %s\n", ksu_stealth_enabled ? "enabled" : "disabled");
}

bool ksu_stealth_is_enabled(void)
{
    if (!ksu_stealth_loaded)
        ksu_stealth_load();

    return ksu_stealth_enabled;
}

int ksu_stealth_set(bool enabled)
{
    char c = enabled ? '1' : '0';
    int ret;

    ret = ksu_stealth_write(&c, 1);
    if (ret)
        return ret;

    ksu_stealth_enabled = enabled;
    ksu_stealth_loaded = true;
    pr_info("stealth: set to %d\n", enabled ? 1 : 0);
    return 0;
}
