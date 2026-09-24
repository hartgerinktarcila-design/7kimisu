#ifndef __KSU_H_STEALTH
#define __KSU_H_STEALTH

#include <linux/types.h>

void ksu_stealth_load(void);
bool ksu_stealth_is_enabled(void);
int ksu_stealth_set(bool enabled);

#endif // __KSU_H_STEALTH
