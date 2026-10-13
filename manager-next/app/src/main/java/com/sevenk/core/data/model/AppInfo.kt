package com.sevenk.core.data.model

import android.content.pm.PackageInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.sevenk.core.Natives

@Parcelize
data class AppInfo(
    val label: String,
    val packageInfo: PackageInfo,
    val profile: Natives.Profile?,
    // packageName 是平台类型 String!：个别畸形/被隐藏的包会给 null，
    // 直接赋给非空 String 会在构造对象时 NPE。兜底成空串。
    val profileKey: String = run {
        val raw: String? = packageInfo.packageName
        raw ?: ""
    },
) : Parcelable {
    val packageName: String
        get() = packageInfo.packageName

    // applicationInfo 理论上不该是 null，但它是平台类型且会被系统裁剪；
    // 旧代码的 !! 一碰就 NPE。兜底成 -1（无效 uid），比闪退安全。
    val uid: Int
        get() = packageInfo.applicationInfo?.uid ?: -1

    val allowSu: Boolean
        get() = profile != null && profile.allowSu

    val hasCustomProfile: Boolean
        get() {
            if (profile == null) {
                return false
            }
            return if (profile.allowSu) {
                !profile.rootUseDefault
            } else {
                !profile.nonRootUseDefault
            }
        }
}
