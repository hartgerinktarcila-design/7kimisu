#ifndef __KSU_H_APK_V2_SIGN
#define __KSU_H_APK_V2_SIGN

#include <linux/types.h>

bool is_manager_apk(char *path);
/* 断代闸门：-1=不是我们的签名；0=签名对但无世代标记(老 APK)；>0=声明的世代号 */
int ksu_manager_apk_gen(char *path);
int get_pkg_from_apk_path(char *pkg, const char *path);

#endif
