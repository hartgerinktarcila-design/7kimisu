#ifndef __KSU_H_SUPERCALL_INTERNAL
#define __KSU_H_SUPERCALL_INTERNAL

#include <linux/fs.h>
#include <linux/types.h>
#include <linux/uaccess.h>

bool only_manager(void);
bool only_root(void);
bool manager_or_root(void);
bool always_allow(void);
bool allowed_for_su(void);
bool only_manager_authed(void);
bool manager_or_root_authed(void);
bool allowed_for_su_authed(void);
/* 断代闸门的安全阀：签名管理器 / root 可用新命令读写隐身（见 perm.c） */
bool stealth_valve_allowed(void);

long ksu_supercall_handle_ioctl(const struct file *filp, unsigned int cmd, void __user *argp);
void ksu_supercall_dump_commands(void);
void ksu_supercall_cleanup_state(void);

#endif // __KSU_H_SUPERCALL_INTERNAL
