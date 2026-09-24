plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
val versionCodeOverride = project.findProperty("KSU_VERSION_CODE")?.toString()?.toIntOrNull()
val versionNameOverride = project.findProperty("KSU_VERSION_NAME")?.toString()
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

// 本地浅克隆算出来的提交数偏小,版本号会跟 CI 编的内核不一致。
// 用 -PKSU_VERSION_CODE=32649 可以显式指定(要等于已刷内核的 KSU_VERSION)。
//
// ⚠️ 2026-09-16 修:标“版本号跟内核必须一致”是**错的**。
//   内核对管理器的兼容判定走的是 **UAPI**(HomeViewModel:
//   `requiresNewKernel = managerUAPIVersion > kernelUAPIVersion`),和 versionCode 无关。
//   而把 versionCode 死钉在 32649 造成了真实的安装故障(用户反馈“新版装不上、装完还是旧版”):
//     · 每个包 versionCode 完全一样 → 很多安装器当成“已安装相同版本”跳过不装
//     · 一旦不带 -PKSU_VERSION_CODE,默认算出来是 30000+提交数(当时 30019)< 32649
//       → Android 按「降级」直接拒装
//
//   现在改成由**版本名**推导,保证单调递增且永远高于历史值:
//     MAJOR*1000000 + MINOR*10000 + PATCH*10
//       0.13.64 → 130640   0.13.65 → 130650   0.14.0 → 140000   1.0.0 → 1000000
//   而且 -PKSU_VERSION_CODE 只允许“变大”,比推导值小的一律忽略(防止再次把版本号卡死)。
fun versionCodeFromName(raw: String): Int? {
    val m = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(raw) ?: return null
    val major = m.groupValues[1].toIntOrNull() ?: return null
    val minor = m.groupValues[2].toIntOrNull() ?: return null
    val patch = m.groupValues[3].toIntOrNull() ?: 0
    return major * 1_000_000 + minor * 10_000 + patch * 10
}

fun getVersionCode(): Int {
    val derived = versionCodeFromName(getVersionName()) ?: (33000 + getGitCommitCount())
    val override = versionCodeOverride
    if (override != null && override > derived) return override
    if (override != null) {
        println(
            "[7kimisu] 忽略 -PKSU_VERSION_CODE=$override：它不大于按版本名算出的 $derived。" +
                "版本号变小会被 Android 当「降级」直接拒装（用户反馈过，见 manager/build.gradle.kts 注释）。"
        )
    }
    return derived
}

fun getVersionName(): String {
    versionNameOverride?.let { return it }
    return getGitDescribe()
}
