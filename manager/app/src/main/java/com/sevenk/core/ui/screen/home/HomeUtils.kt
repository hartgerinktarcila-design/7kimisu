package com.sevenk.core.ui.screen.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat

@Immutable
data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int
)

fun getManagerVersion(context: Context): ManagerVersion {
    // ⚠️ 2026-09-19 修:这里原来是两个 `!!` ——
    //    ① `getPackageInfo(...)!!`:`PackageManager` 找不到包名时会返回 null(或抛
    //       NameNotFoundException),`!!` 立刻 NPE → 首页状态构建崩 → 闪退;
    //    ② `packageInfo.versionName!!`:版本名在极少数包上是 null,同样 NPE。
    //    现在都改成安全取值 + 兜底("未知" / 0),这个函数从此不抛异常。
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    return ManagerVersion(
        versionName = packageInfo?.versionName ?: "未知",
        versionCode = versionCode
    )
}
