#include <jni.h>

#include <sys/prctl.h>
#include <linux/capability.h>
#include <pwd.h>
#include <unistd.h>
#include <sys/wait.h>

#include <android/log.h>
#include <cstring>

#include "ksu.h"
#include "logging.h"

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_getVersion(JNIEnv *env, jobject) {
    int version = get_version();
    if (version > 0) {
        return version;
    }
    // try legacy method as fallback
    return legacy_get_info().first;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_getKernelUAPIVersion(JNIEnv *env, jobject) {
    return get_kernel_uapi_version();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_getManagerUAPIVersion(JNIEnv *env, jobject) {
    return get_manager_uapi_version();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_getSuperuserCount(JNIEnv *env, jobject) {
    struct ksu_new_get_allow_list_cmd cmd = {
        .count = 0
    };
    bool result = get_allow_list(&cmd);
    return result ? cmd.total_count : 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isSafeMode(JNIEnv *env, jclass clazz) {
    return is_safe_mode();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isLkmMode(JNIEnv *env, jclass clazz) {
    return is_lkm_mode();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isLkmBundled(JNIEnv *env, jclass clazz) {
    return is_lkm_bundled();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isLateLoadMode(JNIEnv *env, jclass clazz) {
    return is_late_load_mode();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isManager(JNIEnv *env, jclass clazz) {
    return is_manager();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isPrBuild(JNIEnv *env, jclass clazz) {
    return is_pr_build();
}

/**
 * ══════════════════════════════════════════════════════════════════════
 * JNI 兜底（v2.15，2026-09-22）
 *
 * 背景：`FindClass` / `GetMethodID` / `GetFieldID` 在失败时返回 **NULL 并挂起一个
 * Java 异常**。老代码从来不检查，直接把这个 NULL 拿去调下一个 JNI 函数
 * （例如 `GetMethodID(NULL, …)`）—— ART 对"拿 NULL 当类/jobject 用"的处理是
 * `LOG(FATAL)` → `abort()` → **signal 6 (SIGABRT)**（真机上就是一条 tombstone），
 * 而不是温和地抛个 Java 异常。所以这里统一改成：
 *   · 任何一步拿到 NULL → 清掉挂起异常 → **立刻放弃并返回安全默认值**；
 *   · 任何一步的 jobject/jclass 参数为 NULL → 同样不往下传。
 *
 * 铁律：**JNI 边界上绝不允许"失败之后继续用返回值"**。
 * ══════════════════════════════════════════════════════════════════════
 */

/** 拿到 NULL 句柄 = JNI 已经出错：清掉挂起异常并报 true（调用方必须放弃）。 */
static inline bool jni_null(JNIEnv *env, const void *handle) {
    if (handle != nullptr) {
        return false;
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return true;
}

static void fillIntArray(JNIEnv *env, jobject list, int *data, int count) {
    if (list == nullptr || data == nullptr || count <= 0) {
        return;
    }
    auto cls = env->GetObjectClass(list);
    if (jni_null(env, cls)) return;
    auto add = env->GetMethodID(cls, "add", "(Ljava/lang/Object;)Z");
    if (jni_null(env, add)) return;
    auto integerCls = env->FindClass("java/lang/Integer");
    if (jni_null(env, integerCls)) return;
    auto constructor = env->GetMethodID(integerCls, "<init>", "(I)V");
    if (jni_null(env, constructor)) return;
    for (int i = 0; i < count; ++i) {
        auto integer = env->NewObject(integerCls, constructor, data[i]);
        if (integer == nullptr) {
            env->ExceptionClear();
            return;
        }
        env->CallBooleanMethod(list, add, integer);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
    }
}

static void addIntToList(JNIEnv *env, jobject list, int ele) {
    if (list == nullptr) {
        return;
    }
    auto cls = env->GetObjectClass(list);
    if (jni_null(env, cls)) return;
    auto add = env->GetMethodID(cls, "add", "(Ljava/lang/Object;)Z");
    if (jni_null(env, add)) return;
    auto integerCls = env->FindClass("java/lang/Integer");
    if (jni_null(env, integerCls)) return;
    auto constructor = env->GetMethodID(integerCls, "<init>", "(I)V");
    if (jni_null(env, constructor)) return;
    auto integer = env->NewObject(integerCls, constructor, ele);
    if (integer == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->CallBooleanMethod(list, add, integer);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

static uint64_t capListToBits(JNIEnv *env, jobject list) {
    if (list == nullptr) {
        return 0;
    }
    auto cls = env->GetObjectClass(list);
    if (jni_null(env, cls)) return 0;
    auto get = env->GetMethodID(cls, "get", "(I)Ljava/lang/Object;");
    auto size = env->GetMethodID(cls, "size", "()I");
    if (jni_null(env, get) || jni_null(env, size)) return 0;
    auto listSize = env->CallIntMethod(list, size);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    auto integerCls = env->FindClass("java/lang/Integer");
    if (jni_null(env, integerCls)) return 0;
    auto intValue = env->GetMethodID(integerCls, "intValue", "()I");
    if (jni_null(env, intValue)) return 0;
    uint64_t result = 0;
    for (int i = 0; i < listSize; ++i) {
        auto integer = env->CallObjectMethod(list, get, i);
        if (integer == nullptr) {          // 列表里混进了 null 元素：跳过，不当错误
            env->ExceptionClear();
            continue;
        }
        int data = env->CallIntMethod(integer, intValue);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            continue;
        }

        if (cap_valid(data)) {
            result |= (1ULL << data);
        }
    }

    return result;
}

static int getListSize(JNIEnv *env, jobject list) {
    if (list == nullptr) {
        return 0;
    }
    auto cls = env->GetObjectClass(list);
    if (jni_null(env, cls)) return 0;
    auto size = env->GetMethodID(cls, "size", "()I");
    if (jni_null(env, size)) return 0;
    int n = env->CallIntMethod(list, size);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return n < 0 ? 0 : n;
}

static void fillArrayWithList(JNIEnv *env, jobject list, int *data, int count) {
    if (list == nullptr || data == nullptr || count <= 0) {
        return;
    }
    auto cls = env->GetObjectClass(list);
    if (jni_null(env, cls)) return;
    auto get = env->GetMethodID(cls, "get", "(I)Ljava/lang/Object;");
    if (jni_null(env, get)) return;
    auto integerCls = env->FindClass("java/lang/Integer");
    if (jni_null(env, integerCls)) return;
    auto intValue = env->GetMethodID(integerCls, "intValue", "()I");
    if (jni_null(env, intValue)) return;
    for (int i = 0; i < count; ++i) {
        auto integer = env->CallObjectMethod(list, get, i);
        if (integer == nullptr) {          // 缺元素 / 混进 null：留 0，别把整个调用带崩
            env->ExceptionClear();
            data[i] = 0;
            continue;
        }
        int v = env->CallIntMethod(integer, intValue);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            v = 0;
        }
        data[i] = v;
    }
}

/**
 * 把 Java 字符串安全地拷进**固定容量**的 C 缓冲区（目标必须以 '\0' 结尾）。
 *
 * 修掉的两个坑：
 *  1) 空指针：Kotlin 侧的签名允许传 null（Natives.kt 里 `getAppProfile(key: String?)`），
 *     旧代码上来就 `env->GetStringLength(pkg)` —— 对 null 调用 JNI 函数是空指针解引用 → SIGSEGV。
 *  2) 缓冲区溢出：`KSU_MAX_PACKAGE_NAME` 是**字节**容量，但旧代码用 `GetStringLength`
 *     （UTF-16 码元数）做校验，`strcpy` 拷的却是 **UTF-8 字节**：一个中文字 UTF-8 最多 3 字节，
 *     255 个码元最多写出 765+1 字节 → 撑爆 char[256]。这里改用 `GetStringUTFLength` 拿字节数。
 *
 * 只允许写入 `dst_size - 1` 字节内容，最后 1 字节留给结尾的 '\0'；放不下就整体失败（不截断拷贝），
 * 且用 `memcpy` + 手动写 '\0'，不再用裸 `strcpy`。
 *
 * @return false 表示参数不合法或放不下，调用方必须直接失败返回，绝不能继续拷贝。
 */
static bool copy_string_into(JNIEnv *env, jstring src, char *dst, size_t dst_size) {
    if (src == nullptr || dst == nullptr || dst_size == 0) {
        return false;
    }
    // 注意：这里必须是 GetStringUTFLength（UTF-8 字节数），不能用 GetStringLength（UTF-16 码元数）
    const jsize utf8_len = env->GetStringUTFLength(src);
    if (utf8_len < 0 || static_cast<size_t>(utf8_len) >= dst_size) {
        return false;
    }
    const char *chars = env->GetStringUTFChars(src, nullptr);
    if (chars == nullptr) {
        // 只可能是 OOM（此时 JNI 已抛出 OutOfMemoryError），同样不能继续用
        return false;
    }
    memcpy(dst, chars, static_cast<size_t>(utf8_len));
    dst[utf8_len] = '\0';
    env->ReleaseStringUTFChars(src, chars);
    return true;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_sevenk_core_Natives_getAppProfile(JNIEnv *env, jobject, jstring pkg, jint uid) {
    app_profile profile = {};
    profile.version = KSU_APP_PROFILE_VER;

    // pkg 可能为 null；长度按 UTF-8 字节数校验。
    // 🔴 v2.15：以前 key 非法就 `return nullptr` —— 但 Kotlin 侧签名是
    //    `external fun getAppProfile(key: String?, uid: Int): Profile`（**非空**），
    //    调用方直接 `.umountModules` / `.allowSu`。返回 null = 把 NPE 丢进 Kotlin
    //    （首页/设置页/超级用户页都是"打开就闪退"）。现在一律往下走，
    //    用**默认 profile**（语义与下面 useDefaultProfile 那条分支一致：
    //    没有 profile = 用默认值），永不返回 null。
    const bool keyOk = copy_string_into(env, pkg, profile.key, sizeof(profile.key));
    profile.curr_uid = uid;

    bool useDefaultProfile = !keyOk || get_app_profile(&profile) != 0;

    auto cls = env->FindClass("com/sevenk/core/Natives$Profile");
    if (jni_null(env, cls)) return nullptr;
    auto constructor = env->GetMethodID(cls, "<init>", "()V");
    if (jni_null(env, constructor)) return nullptr;
    auto obj = env->NewObject(cls, constructor);
    if (jni_null(env, obj)) return nullptr;
    auto keyField = env->GetFieldID(cls, "name", "Ljava/lang/String;");
    auto currentUidField = env->GetFieldID(cls, "currentUid", "I");
    auto allowSuField = env->GetFieldID(cls, "allowSu", "Z");

    auto rootUseDefaultField = env->GetFieldID(cls, "rootUseDefault", "Z");
    auto rootTemplateField = env->GetFieldID(cls, "rootTemplate", "Ljava/lang/String;");

    auto uidField = env->GetFieldID(cls, "uid", "I");
    auto gidField = env->GetFieldID(cls, "gid", "I");
    auto groupsField = env->GetFieldID(cls, "groups", "Ljava/util/List;");
    auto capabilitiesField = env->GetFieldID(cls, "capabilities", "Ljava/util/List;");
    auto domainField = env->GetFieldID(cls, "context", "Ljava/lang/String;");
    auto namespacesField = env->GetFieldID(cls, "namespace", "I");
    jfieldID flagsField = env->GetFieldID(cls, "flags", "J");

    auto nonRootUseDefaultField = env->GetFieldID(cls, "nonRootUseDefault", "Z");
    auto umountModulesField = env->GetFieldID(cls, "umountModules", "Z");

    // 字段 ID 任一为 NULL（类被 R8 改过 / 类加载异常）→ 继续 Set*Field 就是 ART abort。
    // 这种"结构性不匹配"宁可返回 null 让上层看到 null，也绝不能让进程 abort。
    if (jni_null(env, keyField) || jni_null(env, currentUidField) || jni_null(env, allowSuField) ||
        jni_null(env, rootUseDefaultField) || jni_null(env, rootTemplateField) ||
        jni_null(env, uidField) || jni_null(env, gidField) || jni_null(env, groupsField) ||
        jni_null(env, capabilitiesField) || jni_null(env, domainField) ||
        jni_null(env, namespacesField) || jni_null(env, flagsField) ||
        jni_null(env, nonRootUseDefaultField) || jni_null(env, umountModulesField)) {
        return nullptr;
    }

    env->SetObjectField(obj, keyField, env->NewStringUTF(profile.key));
    env->SetIntField(obj, currentUidField, profile.curr_uid);

    if (useDefaultProfile) {
        // no profile found, so just use default profile:
        // don't allow root and use default profile!
        LOGD("use default profile for: %s, %d", profile.key, uid);

        // allow_su = false
        // non root use default = true
        env->SetBooleanField(obj, allowSuField, false);
        env->SetBooleanField(obj, nonRootUseDefaultField, true);

        return obj;
    }

    auto allowSu = profile.allow_su;

    if (allowSu) {
        env->SetBooleanField(obj, rootUseDefaultField, (jboolean) profile.rp_config.use_default);
        if (strlen(profile.rp_config.template_name) > 0) {
            env->SetObjectField(obj, rootTemplateField,
                    env->NewStringUTF(profile.rp_config.template_name));
        }

        env->SetIntField(obj, uidField, profile.rp_config.profile.uid);
        env->SetIntField(obj, gidField, profile.rp_config.profile.gid);

        jobject groupList = env->GetObjectField(obj, groupsField);
        int groupCount = profile.rp_config.profile.groups_count;
        if (groupCount > KSU_MAX_GROUPS) {
            LOGD("kernel group count too large: %d???", groupCount);
            groupCount = KSU_MAX_GROUPS;
        }
        fillIntArray(env, groupList, profile.rp_config.profile.groups, groupCount);

        jobject capList = env->GetObjectField(obj, capabilitiesField);
        for (int i = 0; i <= CAP_LAST_CAP; i++) {
            if (profile.rp_config.profile.capabilities.effective & (1ULL << i)) {
                addIntToList(env, capList, i);
            }
        }

        env->SetObjectField(obj, domainField,
                env->NewStringUTF(profile.rp_config.profile.selinux_domain));
        env->SetIntField(obj, namespacesField, profile.rp_config.profile.namespaces);
        env->SetBooleanField(obj, allowSuField, profile.allow_su);
        env->SetLongField(obj, flagsField, (jlong) profile.rp_config.profile.flags);
    } else {
        env->SetBooleanField(obj, nonRootUseDefaultField,
                (jboolean) profile.nrp_config.use_default);
        env->SetBooleanField(obj, umountModulesField, profile.nrp_config.profile.umount_modules);
    }

    return obj;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_setAppProfile(JNIEnv *env, jobject clazz, jobject profile) {
    // 🔴 v2.15：Kotlin 侧签名是 `external fun setAppProfile(profile: Profile?)` ——
    //    **允许传 null**。老代码上来就 `GetObjectField(NULL, …)`：ART 对"拿 NULL 当
    //    jobject 用"的处理是 `LOG(FATAL)` → abort() → 一条 SIGABRT tombstone，
    //    而不是温和的 Java 异常。这里直接判掉。
    if (profile == nullptr) {
        LOGW("setAppProfile called with null profile; ignored");
        return JNI_FALSE;
    }

    auto cls = env->FindClass("com/sevenk/core/Natives$Profile");
    if (jni_null(env, cls)) return JNI_FALSE;

    auto keyField = env->GetFieldID(cls, "name", "Ljava/lang/String;");
    auto currentUidField = env->GetFieldID(cls, "currentUid", "I");
    auto allowSuField = env->GetFieldID(cls, "allowSu", "Z");

    auto rootUseDefaultField = env->GetFieldID(cls, "rootUseDefault", "Z");
    auto rootTemplateField = env->GetFieldID(cls, "rootTemplate", "Ljava/lang/String;");

    auto uidField = env->GetFieldID(cls, "uid", "I");
    auto gidField = env->GetFieldID(cls, "gid", "I");
    auto groupsField = env->GetFieldID(cls, "groups", "Ljava/util/List;");
    auto capabilitiesField = env->GetFieldID(cls, "capabilities", "Ljava/util/List;");
    auto domainField = env->GetFieldID(cls, "context", "Ljava/lang/String;");
    auto namespacesField = env->GetFieldID(cls, "namespace", "I");
    jfieldID flagsField = env->GetFieldID(cls, "flags", "J");

    auto nonRootUseDefaultField = env->GetFieldID(cls, "nonRootUseDefault", "Z");
    auto umountModulesField = env->GetFieldID(cls, "umountModules", "Z");

    if (jni_null(env, keyField) || jni_null(env, currentUidField) || jni_null(env, allowSuField) ||
        jni_null(env, rootUseDefaultField) || jni_null(env, rootTemplateField) ||
        jni_null(env, uidField) || jni_null(env, gidField) || jni_null(env, groupsField) ||
        jni_null(env, capabilitiesField) || jni_null(env, domainField) ||
        jni_null(env, namespacesField) || jni_null(env, flagsField) ||
        jni_null(env, nonRootUseDefaultField) || jni_null(env, umountModulesField)) {
        return JNI_FALSE;
    }

    auto key = env->GetObjectField(profile, keyField);
    if (!key) {
        return false;
    }

    app_profile p = {};
    p.version = KSU_APP_PROFILE_VER;

    // 与 getAppProfile 同样的两个坑：字段可能为 null、旧校验用的是 UTF-16 长度而 strcpy 拷的是
    // UTF-8 字节（中文 key 会溢出）。现在按字节校验，为 null 或放不下都直接失败。
    if (!copy_string_into(env, (jstring) key, p.key, sizeof(p.key))) {
        return false;
    }

    auto currentUid = env->GetIntField(profile, currentUidField);

    auto uid = env->GetIntField(profile, uidField);
    auto gid = env->GetIntField(profile, gidField);
    auto groups = env->GetObjectField(profile, groupsField);
    auto capabilities = env->GetObjectField(profile, capabilitiesField);
    auto domain = env->GetObjectField(profile, domainField);
    auto allowSu = env->GetBooleanField(profile, allowSuField);
    auto umountModules = env->GetBooleanField(profile, umountModulesField);

    p.allow_su = allowSu;
    p.curr_uid = currentUid;

    if (allowSu) {
        p.rp_config.use_default = env->GetBooleanField(profile, rootUseDefaultField);
        auto templateName = env->GetObjectField(profile, rootTemplateField);
        // 旧代码对模板名**没有任何长度校验**就 strcpy 进 char[256]，模板名一超就溢出；
        // 现在按 UTF-8 字节数校验，为 null 或放不下就留空（比撑爆缓冲区安全）。
        copy_string_into(env, (jstring) templateName, p.rp_config.template_name,
                         sizeof(p.rp_config.template_name));

        p.rp_config.profile.uid = uid;
        p.rp_config.profile.gid = gid;

        int groups_count = getListSize(env, groups);
        if (groups_count > KSU_MAX_GROUPS) {
            LOGD("groups count too large: %d", groups_count);
            return false;
        }
        p.rp_config.profile.groups_count = groups_count;
        fillArrayWithList(env, groups, p.rp_config.profile.groups, groups_count);

        p.rp_config.profile.capabilities.effective = capListToBits(env, capabilities);

        // selinux_domain 只有 KSU_SELINUX_DOMAIN(64) 字节，旧代码同样"无校验 + 裸 strcpy"，
        // 且 domain 为 null 时会直接拿 null 去调 JNI；现在按字节校验、为 null 就留空。
        copy_string_into(env, (jstring) domain, p.rp_config.profile.selinux_domain,
                         sizeof(p.rp_config.profile.selinux_domain));

        p.rp_config.profile.namespaces = env->GetIntField(profile, namespacesField);

        p.rp_config.profile.flags = env->GetLongField(profile, flagsField);
    } else {
        p.nrp_config.use_default = env->GetBooleanField(profile, nonRootUseDefaultField);
        p.nrp_config.profile.umount_modules = umountModules;
    }

    return set_app_profile(&p);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_uidShouldUmount(JNIEnv *env, jobject thiz, jint uid) {
    return uid_should_umount(uid);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isSuEnabled(JNIEnv *env, jobject thiz) {
    return is_su_enabled();
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_setSuEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_su_enabled(enabled);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isKernelUmountEnabled(JNIEnv *env, jobject thiz) {
    return is_kernel_umount_enabled();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_setKernelUmountEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_kernel_umount_enabled(enabled);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_isSelinuxHideEnabled(JNIEnv *env, jobject thiz) {
    return is_selinux_hide_enabled();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_setSelinuxHideEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_selinux_hide_enabled(enabled);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_sevenk_core_Natives_getUserName(JNIEnv *env, jobject thiz, jint uid) {
    struct passwd *pw = getpwuid((uid_t) uid);
    if (pw && pw->pw_name && pw->pw_name[0] != '\0') {
        return env->NewStringUTF(pw->pw_name);
    }
    return nullptr;
}

int fork_dont_care_and_exec_ksud(const char *path, const char *pkg) {
    // 🔴 v2.15：这两个参数来自 JNI（`GetStringUTFChars` 失败时会**返回 NULL**），
    //    老代码直接往 execl 里传。`path == NULL` 会让 execl 去 execve(NULL,…)
    //    （ERRNO 是 EFAULT，不致命）但 `pkg == NULL` 会出现在 argv 中间 ——
    //    真正危险的是**绝不发起一个参数已经坏掉的 root 进程**。一律判掉再走。
    if (path == nullptr || path[0] == '\0' || pkg == nullptr) {
        LOGE("fork_dont_care_and_exec_ksud: bad args (path=%s pkg=%s)",
             path ? path : "(null)", pkg ? pkg : "(null)");
        return -1;
    }

    int pid = fork();
    if (pid < 0) {
        PLOGE("fork");
        return pid;
    } else if (pid > 0) {
        int status = 0;
        if (TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) < 0) {
            PLOGE("waitpid");
            return -1;
        }
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
            LOGE("magica bootstrap child failed, status=%d", status);
        }
        return pid;
    }

    if (setuid(0) != 0) {
        PLOGE("setuid");
        _exit(1);
    }

    pid = fork();
    if (pid < 0) {
        PLOGE("fork 2");
        _exit(1);
    } else if (pid > 0) {
        _exit(0);
    }

    execl(path, "ksud", "late-load", "--magica", "5555", "--package-name", pkg, nullptr);
    PLOGE("exec magica");
    _exit(1);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sevenk_core_magica_AppZygotePreload_forkDontCareAndExecKsud(JNIEnv *env, jclass clazz,
                                                                        jstring ksud_path, jstring pkg_name) {
    // 🔴 v2.15：参数可能是 null（Kotlin/Java 侧没保证），GetStringUTFChars 也可能返回
    //    NULL（OOM 并把异常挂起）。老代码不看就 `LOGD("%s", path)` —— 在 bionic 上给
    //    `%s` 传 NULL 属于未定义行为。现在先判掉、释放干净、直接返回。
    if (ksud_path == nullptr || pkg_name == nullptr) {
        LOGE("forkDontCareAndExecKsud: null jstring (path=%p pkg=%p)",
             (void *) ksud_path, (void *) pkg_name);
        return;
    }
    auto path = env->GetStringUTFChars(ksud_path, nullptr);
    if (path == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("forkDontCareAndExecKsud: GetStringUTFChars(ksud_path) failed");
        return;
    }
    auto pkg = env->GetStringUTFChars(pkg_name, nullptr);
    if (pkg == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->ReleaseStringUTFChars(ksud_path, path);
        LOGE("forkDontCareAndExecKsud: GetStringUTFChars(pkg_name) failed");
        return;
    }
    LOGD("executing magica %s (pkg %s)", path, pkg);
    fork_dont_care_and_exec_ksud(path, pkg);
    env->ReleaseStringUTFChars(ksud_path, path);
    env->ReleaseStringUTFChars(pkg_name, pkg);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_appLockState(JNIEnv *env, jobject) {
    uint8_t configured = 0;
    uint8_t authed = 0;
    if (!app_lock_state(&configured, &authed)) {
        return -1;
    }
    return (configured ? 1 : 0) | (authed ? 2 : 0);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_appLockAuth(JNIEnv *env, jobject, jbyteArray hash) {
    if (hash == nullptr || env->GetArrayLength(hash) != 32) {
        return JNI_FALSE;
    }
    uint8_t buf[32];
    env->GetByteArrayRegion(hash, 0, 32, reinterpret_cast<jbyte *>(buf));
    return app_lock_auth(buf) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_appLockSet(JNIEnv *env, jobject, jbyteArray hash, jboolean disable) {
    uint8_t buf[32] = { 0 };
    if (hash != nullptr && env->GetArrayLength(hash) == 32) {
        env->GetByteArrayRegion(hash, 0, 32, reinterpret_cast<jbyte *>(buf));
    }
    uint8_t configured = 0;
    return app_lock_set(buf, disable == JNI_TRUE, &configured) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sevenk_core_Natives_stealthState(JNIEnv *env, jobject) {
    bool enabled = false;
    if (!stealth_get(&enabled)) {
        return -1;
    }
    return enabled ? 1 : 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_sevenk_core_Natives_stealthSet(JNIEnv *env, jobject, jboolean enabled) {
    return stealth_set(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}
