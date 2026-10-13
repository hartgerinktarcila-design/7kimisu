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

// 重写版从 v3.0.0 起跳：versionCode 公式沿用旧规则 MAJOR*1000000+MINOR*10000+PATCH*10，
// v3.0.0 → 3000000，恒高于旧版 v2.29 的 2290000，老用户正常覆盖升级。
// -PKSU_VERSION_NAME / -PKSU_VERSION_CODE 可覆盖（CODE 只允许变大）。
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun versionCodeFromName(raw: String): Int? {
    val m = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(raw) ?: return null
    val major = m.groupValues[1].toIntOrNull() ?: return null
    val minor = m.groupValues[2].toIntOrNull() ?: return null
    val patch = m.groupValues[3].toIntOrNull() ?: 0
    return major * 1_000_000 + minor * 10_000 + patch * 10
}

fun getVersionCode(): Int {
    val derived = versionCodeFromName(getVersionName()) ?: 3_000_000
    val override = project.findProperty("KSU_VERSION_CODE")?.toString()?.toIntOrNull()
    if (override != null && override > derived) return override
    return derived
}

fun getVersionName(): String {
    return project.findProperty("KSU_VERSION_NAME")?.toString() ?: "v3.0.0"
}
