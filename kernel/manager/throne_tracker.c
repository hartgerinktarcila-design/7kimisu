#include <linux/err.h>
#include <linux/fs.h>
#include <linux/list.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/types.h>
#include <linux/version.h>

#include "policy/allowlist.h"
#include "manager/apk_sign.h"
#include "klog.h" // IWYU pragma: keep
#include "manager/manager_identity.h"
#include "manager/throne_tracker.h"

uid_t ksu_manager_appid = KSU_INVALID_APPID;
/* 见 manager_identity.h：只给安全阀用（签名对就算，不要求世代） */
uid_t ksu_signed_manager_appid = KSU_INVALID_APPID;

#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"

struct uid_data {
    struct list_head list;
    u32 uid;
    char package[KSU_MAX_PACKAGE_NAME];
};

/* 在 packages.list 里按包名找 uid（认主与"记签名候选"共用） */
static bool lookup_uid_by_pkg(const char *pkg, struct list_head *uid_data, u32 *uid_out)
{
    struct list_head *list = (struct list_head *)uid_data;
    struct uid_data *np;

    list_for_each_entry (np, list, list) {
        if (strncmp(np->package, pkg, KSU_MAX_PACKAGE_NAME) == 0) {
            *uid_out = np->uid;
            return true;
        }
    }
    return false;
}

static void crown_manager(const char *apk, struct list_head *uid_data)
{
    char pkg[KSU_MAX_PACKAGE_NAME];
    u32 uid;

    if (get_pkg_from_apk_path(pkg, apk) < 0) {
        pr_err("Failed to get package name from apk path: %s\n", apk);
        return;
    }

    pr_info("manager pkg: %s\n", pkg);

    if (lookup_uid_by_pkg(pkg, uid_data, &uid)) {
        pr_info("Crowning manager: %s(uid=%d)\n", pkg, uid);
        ksu_set_manager_appid(uid);
    }
}

/*
 * 「签名对，但（可能）没过世代闸门」的管理器：记下它的 uid 供安全阀使用。
 * 闸门判定本身不改这个值 —— 老 APK 也会命中这里，但它调不了新命令。
 */
static void note_signed_manager(const char *apk, struct list_head *uid_data)
{
    char pkg[KSU_MAX_PACKAGE_NAME];
    u32 uid;

    if (get_pkg_from_apk_path(pkg, apk) < 0)
        return;
    if (lookup_uid_by_pkg(pkg, uid_data, &uid)) {
        pr_info("signed manager candidate: %s(uid=%d)\n", pkg, uid);
        ksu_set_signed_manager_appid(uid);
    }
}


#define DATA_PATH_LEN 384 // 384 is enough for /data/app/<package>/base.apk

struct data_path {
    char dirpath[DATA_PATH_LEN];
    int depth;
    struct list_head list;
};

struct apk_path_hash {
    unsigned int hash;
    bool exists;
    struct list_head list;
};

static struct list_head apk_path_hash_list = LIST_HEAD_INIT(apk_path_hash_list);

struct my_dir_context {
    struct dir_context ctx;
    struct list_head *data_path_list;
    char *parent_dir;
    void *private_data;
    int depth;
    int *stop;
};
// https://docs.kernel.org/filesystems/porting.html
// filldir_t (readdir callbacks) calling conventions have changed. Instead of returning 0 or -E... it returns bool now. false means "no more" (as -E... used to) and true - "keep going" (as 0 in old calling conventions). Rationale: callers never looked at specific -E... values anyway. -> iterate_shared() instances require no changes at all, all filldir_t ones in the tree converted.
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)
#define FILLDIR_RETURN_TYPE bool
#define FILLDIR_ACTOR_CONTINUE true
#define FILLDIR_ACTOR_STOP false
#else
#define FILLDIR_RETURN_TYPE int
#define FILLDIR_ACTOR_CONTINUE 0
#define FILLDIR_ACTOR_STOP -EINVAL
#endif
FILLDIR_RETURN_TYPE my_actor(struct dir_context *ctx, const char *name, int namelen, loff_t off, u64 ino,
                             unsigned int d_type)
{
    struct my_dir_context *my_ctx = container_of(ctx, struct my_dir_context, ctx);
    char dirpath[DATA_PATH_LEN];

    if (!my_ctx) {
        pr_err("Invalid context\n");
        return FILLDIR_ACTOR_STOP;
    }
    if (my_ctx->stop && *my_ctx->stop) {
        pr_info("Stop searching\n");
        return FILLDIR_ACTOR_STOP;
    }

    if (!strncmp(name, "..", namelen) || !strncmp(name, ".", namelen))
        return FILLDIR_ACTOR_CONTINUE; // Skip "." and ".."

    if (d_type == DT_DIR && namelen >= 8 && !strncmp(name, "vmdl", 4) && !strncmp(name + namelen - 4, ".tmp", 4)) {
        pr_info("Skipping directory: %.*s\n", namelen, name);
        return FILLDIR_ACTOR_CONTINUE; // Skip staging package
    }

    if (snprintf(dirpath, DATA_PATH_LEN, "%s/%.*s", my_ctx->parent_dir, namelen, name) >= DATA_PATH_LEN) {
        pr_err("Path too long: %s/%.*s\n", my_ctx->parent_dir, namelen, name);
        return FILLDIR_ACTOR_CONTINUE;
    }

    if (d_type == DT_DIR && my_ctx->depth > 0 && (my_ctx->stop && !*my_ctx->stop)) {
        struct data_path *data = kzalloc(sizeof(struct data_path), GFP_KERNEL);

        if (!data) {
            pr_err("Failed to allocate memory for %s\n", dirpath);
            return FILLDIR_ACTOR_CONTINUE;
        }

        strscpy(data->dirpath, dirpath, DATA_PATH_LEN);
        data->depth = my_ctx->depth - 1;
        list_add_tail(&data->list, my_ctx->data_path_list);
    } else {
        if ((namelen == 8) && (strncmp(name, "base.apk", namelen) == 0)) {
            struct apk_path_hash *pos, *n;
            unsigned int hash = full_name_hash(NULL, dirpath, strlen(dirpath));
            list_for_each_entry (pos, &apk_path_hash_list, list) {
                if (hash == pos->hash) {
                    pos->exists = true;
                    return FILLDIR_ACTOR_CONTINUE;
                }
            }

            /*
             * 断代闸门：ksu_manager_apk_gen() 一次给出两个事实
             *   -1 → 不是我们签的包        → 和以前一样，只进 APK 缓存
             *    0 → 签名对但没世代标记    → **老版本管理器**，绝不认主
             *   >0 → 签名对 + 世代号 N     → N >= KSU_MANAGER_MIN_GEN 才认主
             *
             * 与以前唯一的区别就在这一处判断：以前 `is_manager_apk()`
             * （只看签名）为真就认主，现在多要一个 APK 自带的世代标记。
             */
            int gen = ksu_manager_apk_gen(dirpath);
            pr_info("Found new base.apk at path: %s, manager gen: %d\n", dirpath, gen);

            if (gen >= 0) {
                /* 签名对 → 记成"安全阀候选"（哪怕世代不达标、哪怕不被认主） */
                note_signed_manager(dirpath, my_ctx->private_data);
            }

            if (gen >= KSU_MANAGER_MIN_GEN) {
                crown_manager(dirpath, my_ctx->private_data);
                *my_ctx->stop = 1;

                // Manager found, clear APK cache list
                list_for_each_entry_safe (pos, n, &apk_path_hash_list, list) {
                    list_del(&pos->list);
                    kfree(pos);
                }
            } else {
                if (gen == 0)
                    pr_warn("manager apk rejected by generation gate (no gen marker, need %d): %s\n",
                            KSU_MANAGER_MIN_GEN, dirpath);
                else if (gen > 0)
                    pr_warn("manager apk rejected by generation gate (gen %d < %d): %s\n", gen,
                            KSU_MANAGER_MIN_GEN, dirpath);

                struct apk_path_hash *apk_data = kzalloc(sizeof(struct apk_path_hash), GFP_KERNEL);
                if (!apk_data) {
                    pr_err("Failed to allocate apk_path_hash for %s\n", dirpath);
                    return FILLDIR_ACTOR_CONTINUE;
                }
                apk_data->hash = hash;
                apk_data->exists = true;
                list_add_tail(&apk_data->list, &apk_path_hash_list);
            }
        }
    }

    return FILLDIR_ACTOR_CONTINUE;
}

void search_manager(const char *path, int depth, struct list_head *uid_data)
{
    int i, stop = 0;
    struct list_head data_path_list;
    INIT_LIST_HEAD(&data_path_list);
    unsigned long data_app_magic = 0;

    // Initialize APK cache list
    struct apk_path_hash *pos, *n;
    list_for_each_entry (pos, &apk_path_hash_list, list) {
        pos->exists = false;
    }

    // First depth
    struct data_path data;
    strscpy(data.dirpath, path, DATA_PATH_LEN);
    data.depth = depth;
    list_add_tail(&data.list, &data_path_list);

    for (i = depth; i >= 0; i--) {
        struct data_path *pos, *n;

        list_for_each_entry_safe (pos, n, &data_path_list, list) {
            struct my_dir_context ctx = { .ctx.actor = my_actor,
                                          .data_path_list = &data_path_list,
                                          .parent_dir = pos->dirpath,
                                          .private_data = uid_data,
                                          .depth = pos->depth,
                                          .stop = &stop };
            struct file *file;

            if (!stop) {
                file = filp_open(pos->dirpath, O_RDONLY | O_NOFOLLOW, 0);
                if (IS_ERR(file)) {
                    pr_err("Failed to open directory: %s, err: %ld\n", pos->dirpath, PTR_ERR(file));
                    goto skip_iterate;
                }

                // grab magic on first folder, which is /data/app
                if (!data_app_magic) {
                    if (file->f_inode->i_sb->s_magic) {
                        data_app_magic = file->f_inode->i_sb->s_magic;
                        pr_info("%s: dir: %s got magic! 0x%lx\n", __func__, pos->dirpath, data_app_magic);
                    } else {
                        filp_close(file, NULL);
                        goto skip_iterate;
                    }
                }

                if (file->f_inode->i_sb->s_magic != data_app_magic) {
                    pr_info("%s: skip: %s magic: 0x%lx expected: 0x%lx\n", __func__, pos->dirpath,
                            file->f_inode->i_sb->s_magic, data_app_magic);
                    filp_close(file, NULL);
                    goto skip_iterate;
                }

                iterate_dir(file, &ctx.ctx);
                filp_close(file, NULL);
            }
        skip_iterate:
            list_del(&pos->list);
            if (pos != &data)
                kfree(pos);
        }
    }

    // Remove stale cached APK entries
    list_for_each_entry_safe (pos, n, &apk_path_hash_list, list) {
        if (!pos->exists) {
            list_del(&pos->list);
            kfree(pos);
        }
    }
}

static bool is_uid_exist(uid_t uid, char *package, void *data)
{
    struct list_head *list = (struct list_head *)data;
    struct uid_data *np;

    bool exist = false;
    list_for_each_entry (np, list, list) {
        if (np->uid == uid % PER_USER_RANGE && strncmp(np->package, package, KSU_MAX_PACKAGE_NAME) == 0) {
            exist = true;
            break;
        }
    }
    return exist;
}

void track_throne(bool prune_only)
{
    struct file *fp = filp_open(SYSTEM_PACKAGES_LIST_PATH, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("%s: open " SYSTEM_PACKAGES_LIST_PATH " failed: %ld\n", __func__, PTR_ERR(fp));
        return;
    }

    struct list_head uid_list;
    INIT_LIST_HEAD(&uid_list);

    char chr = 0;
    loff_t pos = 0;
    loff_t line_start = 0;
    char buf[KSU_MAX_PACKAGE_NAME];
    for (;;) {
        ssize_t count = kernel_read(fp, &chr, sizeof(chr), &pos);
        if (count != sizeof(chr))
            break;
        if (chr != '\n')
            continue;

        count = kernel_read(fp, buf, sizeof(buf) - 1, &line_start);
        if (count <= 0) {
            break;
        }
        buf[count] = '\0';

        struct uid_data *data = kzalloc(sizeof(struct uid_data), GFP_KERNEL);
        if (!data) {
            filp_close(fp, 0);
            goto out;
        }

        char *tmp = buf;
        const char *delim = " ";
        char *package = strsep(&tmp, delim);
        char *uid = strsep(&tmp, delim);
        if (!uid || !package) {
            kfree(data);
            pr_err("update_uid: package or uid is NULL!\n");
            break;
        }

        u32 res;
        if (kstrtou32(uid, 10, &res)) {
            kfree(data);
            pr_err("update_uid: uid parse err\n");
            break;
        }
        data->uid = res;
        strscpy(data->package, package, sizeof(data->package));
        list_add_tail(&data->list, &uid_list);
        // reset line start
        line_start = pos;
    }
    filp_close(fp, 0);

    // now update uid list
    struct uid_data *np;
    struct uid_data *n;

    if (prune_only)
        goto prune;

    // first, check if manager_uid exist!
    bool manager_exist = false;
    list_for_each_entry (np, &uid_list, list) {
        if (np->uid == ksu_get_manager_appid()) {
            manager_exist = true;
            break;
        }
    }

    if (!manager_exist) {
        if (ksu_is_manager_appid_valid()) {
            /*
             * 2026-09-16 修:作废之后**必须继续往下搜**,不能 goto prune。
             *
             * 原来的写法是「作废 → goto prune」,也就是只把失效的 appid 作废、
             * **不当场重新搜索**;真正认主要等下一次 /data/system/packages.list 变化
             * (由 pkg_observer 的 fsnotify 触发)才会走到下面的 search_manager。
             * 等不到那一次事件,界面就一直是「未安装」,只有重启才恢复
             * (开机走 init.c 的 track_throne(false),那是完整流程)。
             *
             * 触发场景:卸载重装会换 Android UID,旧 appid 立刻失效
             * —— 这是用户反馈「装完显示未安装」的根因。
             */
            pr_info("manager is uninstalled, invalidate it!\n");
            ksu_invalidate_manager_uid();
        }
        pr_info("Searching manager...\n");
        /* 重扫前清空"安全阀候选"：避免残留上一次的 uid（uid 会被系统复用） */
        ksu_set_signed_manager_appid(KSU_INVALID_APPID);
        search_manager("/data/app", 2, &uid_list);
        pr_info("Search manager finished\n");
    }

prune:
    // then prune the allowlist
    ksu_prune_allowlist(is_uid_exist, &uid_list);
out:
    // free uid_list
    list_for_each_entry_safe (np, n, &uid_list, list) {
        list_del(&np->list);
        kfree(np);
    }
}

void __init ksu_throne_tracker_init()
{
    // nothing to do
}

void __exit ksu_throne_tracker_exit()
{
    struct apk_path_hash *pos, *n;

    list_for_each_entry_safe (pos, n, &apk_path_hash_list, list) {
        list_del(&pos->list);
        kfree(pos);
    }
}
