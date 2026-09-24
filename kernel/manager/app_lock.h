#ifndef __KSU_H_APP_LOCK
#define __KSU_H_APP_LOCK

#include <linux/types.h>

void ksu_app_lock_load(void);
void ksu_app_lock_reset_load(void);
bool ksu_app_lock_is_configured(void);
bool ksu_app_lock_is_authed(void);
bool ksu_app_lock_ok(void);
int ksu_app_lock_auth(const u8 *hash);
int ksu_app_lock_set(const u8 *hash, bool disable);

#endif // __KSU_H_APP_LOCK
