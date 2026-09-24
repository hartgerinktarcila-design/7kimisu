package com.sevenk.core

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import com.sevenk.core.Natives.Profile.RootProfileFlag
import com.sevenk.core.ui.util.rootAvailable

/**
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {
    // minimal supported kernel version
    // 10915: allowlist breaking change, add app profile
    // 10931: app profile struct add 'version' field
    // 10946: add capabilities
    // 10977: change groups_count and groups to avoid overflow write
    // 11071: Fix the issue of failing to set a custom SELinux type.
    // 12143: breaking: new supercall impl
    // 32310: new get_allow_list ioctl
    // 32336: new set_sepolicy ioctl
    // 32377: add set_init_pgrp ioctl
    // 32513: add uapi version
    const val MINIMAL_SUPPORTED_KERNEL = 32513

    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    init {
        System.loadLibrary("sevenk")
    }

    val version: Int
        external get

    val isSafeMode: Boolean
        external get

    val isLkmMode: Boolean
        external get

    val isLkmBundled: Boolean
        external get

    val isLateLoadMode: Boolean
        external get

    val isManager: Boolean
        external get

    val isPrBuild: Boolean
        external get

    external fun uidShouldUmount(uid: Int): Boolean

    /** bit0 = lock configured, bit1 = authed this boot, -1 = kernel too old */
    external fun appLockState(): Int

    external fun appLockAuth(hash: ByteArray): Boolean

    external fun appLockSet(hash: ByteArray, disable: Boolean): Boolean

    /** 隐身模式:1 = 开启, 0 = 关闭, -1 = 内核不支持 */
    external fun stealthState(): Int

    external fun stealthSet(enabled: Boolean): Boolean

    /**
     * Get the profile of the given package.
     * @param key usually the package name
     * @return 🟢 v2.15：**可空**。native 只在"结构性 JNI 失败"（`Natives$Profile`
     *         类/字段拿不到，例如被 R8 改坏）时返回 null；正常路径**永不返回 null**
     *         （key 为 null / 装不下时会返回一个合法的**默认 Profile**，
     *          见 `cpp/jni.cc` 的 `getAppProfile`）。
     *         以前这里声明成非空 + native 会返回 null，调用方直接 `.copy()` / `.umountModules`
     *         就是一次 NPE 闪退 —— 契约必须和实现一致。
     */
    external fun getAppProfile(key: String?, uid: Int): Profile?
    external fun setAppProfile(profile: Profile?): Boolean

    /**
     * `su` compat mode can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSuEnabled(): Boolean
    external fun setSuEnabled(enabled: Boolean): Boolean

    /**
     * Kernel module umount can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isKernelUmountEnabled(): Boolean
    external fun setKernelUmountEnabled(enabled: Boolean): Boolean

    /**
     * SELinux hide can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSelinuxHideEnabled(): Boolean
    external fun setSelinuxHideEnabled(enabled: Boolean): Int

    /**
     * Get the user name for the uid.
     */
    external fun getUserName(uid: Int): String?

    external fun getSuperuserCount(): Int

    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        Profile(
            NON_ROOT_DEFAULT_PROFILE_KEY,
            NOBODY_UID,
            false,
            umountModules = umountModules
        ).let {
            return setAppProfile(it)
        }
    }

    fun isDefaultUmountModules(): Boolean {
        // 🟢 v2.15：getAppProfile 现在声明成可空（native 结构性 JNI 失败时返回 null）。
        //    读不到就按**产品默认值** true 处理 —— 与 `Profile.umountModules` 的默认一致，
        //    也和旧行为（native 正常时那条 useDefaultProfile 分支给的就是默认值）一致。
        return getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID)?.umountModules ?: true
    }

    val kernelUAPIVersion: Int
        external get

    val managerUAPIVersion: Int
        external get

    fun isFullFeatured(): Boolean {
        // UAPI 的升级只做"新增",新内核向后兼容旧管理器。
        // 所以只要【内核 UAPI >= 管理器 UAPI】就算完整可用,不再要求完全相等。
        // (要求相等会导致:内核比管理器新时,界面被误判成"未安装"而降级)
        return isManager && kernelUAPIVersion >= managerUAPIVersion && rootAvailable()
    }

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal,

        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!

        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
