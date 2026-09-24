package com.sevenk.core.data.model

import android.content.pm.PackageInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.sevenk.core.Natives

const val WEBVIEW_ZYGOTE_UID = 1053
const val WEBVIEW_ZYGOTE_PROFILE_KEY = "webview_zygote"

@Parcelize
data class AppInfo(
    val label: String,
    val packageInfo: PackageInfo,
    val profile: Natives.Profile?,
    // packageName 是平台类型 String!：个别畸形/被隐藏的包会给 null，
    // 直接赋给非空 String 会在构造对象时 NPE（崩溃）。兜底成空串。
    // 🟡（v2.19）清掉编译器那条"Elvis 左值非空"的存量 warning，**行为一字不变**：
    //    先落到显式可空局部量（`String!` ⇒ `String?`，判空因此是真的、不会被编译器
    //    当成非空接收者优化掉），再 `?: ""`。不能改用 `.orEmpty()` —— 那有被当成
    //    非空接收者而省掉判空的风险，而这一行兜不住就是构造 AppInfo 时 NPE 崩溃。
    val profileKey: String = run {
        val raw: String? = packageInfo.packageName
        raw ?: ""
    },
    val special: Boolean = false,
) : Parcelable {
    val packageName: String
        get() = packageInfo.packageName

    val displayIdentifier: String
        get() = if (special) profileKey else packageName
    // applicationInfo 理论上不该是 null，但它是平台类型且会被系统裁剪；
    // 旧代码的 !! 一碰就 NPE。兜底成 -1（无效 uid），比闪退安全。
    val uid: Int
        get() = packageInfo.applicationInfo?.uid ?: -1

    val isWebViewZygote: Boolean
        get() = special && uid == WEBVIEW_ZYGOTE_UID

    val allowSu: Boolean
        get() = !isWebViewZygote && profile != null && profile.allowSu
    val hasCustomProfile: Boolean
        get() {
            if (profile == null) {
                return false
            }

            return if (isWebViewZygote) {
                !profile.nonRootUseDefault
            } else if (profile.allowSu) {
                !profile.rootUseDefault
            } else {
                !profile.nonRootUseDefault
            }
        }
}
