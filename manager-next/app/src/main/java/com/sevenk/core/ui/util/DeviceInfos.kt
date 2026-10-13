package com.sevenk.core.ui.util

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

private const val TAG = "DeviceInfos"

private const val SAMSUNG_FLOATING_FEATURE_CLASS = "com.samsung.android.feature.SemFloatingFeature"
private const val KEY_SAMSUNG_PRODUCT_NAME = "SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"

private val SAMSUNG_FLOATING_FEATURE_PATHS = listOf(
    "/vendor/etc/floating_feature.xml",
    "/system/etc/floating_feature.xml",
)

/**
 * Resolve the commercial device name.
 * 1. Dispatch by [Build.MANUFACTURER] — each vendor branch uses its own property
 *    or dedicated helper (e.g. Samsung).
 * 2. If the vendor branch returns nothing, fallback to MANUFACTURER + BRAND + MODEL.
 */
fun resolveDeviceName(): String {
    val marketName = when (Build.MANUFACTURER.orEmpty().lowercase()) {
        "samsung" -> getSamsungProductName()
        "xiaomi" -> getSystemProperty("ro.product.marketname").takeIfValidDeviceName()
        "oppo", "oneplus", "realme", "oplus" ->
            getSystemProperty("ro.vendor.oplus.market.name").takeIfValidDeviceName()

        "vivo" -> getSystemProperty("ro.vivo.market.name").takeIfValidDeviceName()
        "honor", "huawei" -> getSystemProperty("ro.config.marketing_name").takeIfValidDeviceName()
        "zte", "nubia" -> getSystemProperty("ro.vendor.product.ztename").takeIfValidDeviceName()

        else -> null
    }
    if (marketName != null) return marketName

    return buildString {
        append(Build.MANUFACTURER)
        if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) append(" ").append(Build.BRAND)
        append(" ").append(Build.MODEL)
    }
}

@SuppressLint("PrivateApi")
private fun getSystemProperty(key: String): String? = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, key) as? String
} catch (_: Throwable) {
    null
}

private fun getSamsungProductName(): String? {
    val name = getSamsungProductNameFromFloatingFeature()
        ?: getSamsungProductNameFromFloatingFeatureFile()
    return name.withSamsungPrefix()
}

@SuppressLint("PrivateApi")
private fun getSamsungProductNameFromFloatingFeature(): String? = try {
    val clazz = Class.forName(SAMSUNG_FLOATING_FEATURE_CLASS)
    val instance = clazz.getMethod("getInstance").invoke(null)
    val value = clazz.getMethod("getString", String::class.java)
        .invoke(instance, KEY_SAMSUNG_PRODUCT_NAME) as? String
    value.takeIfValidDeviceName()
} catch (_: Throwable) {
    null
}

private fun getSamsungProductNameFromFloatingFeatureFile(): String? {
    val pattern = Regex(
        "<$KEY_SAMSUNG_PRODUCT_NAME>\\s*(.*?)\\s*</$KEY_SAMSUNG_PRODUCT_NAME>",
        RegexOption.DOT_MATCHES_ALL,
    )
    return SAMSUNG_FLOATING_FEATURE_PATHS.firstNotNullOfOrNull { path ->
        try {
            pattern.find(File(path).readText())?.groupValues?.getOrNull(1).takeIfValidDeviceName()
        } catch (_: Throwable) {
            null
        }
    }
}

private fun String?.takeIfValidDeviceName(): String? =
    this?.trim()?.takeIf {
        it.isNotEmpty() &&
                !it.equals(Build.UNKNOWN, ignoreCase = true) &&
                !it.equals("null", ignoreCase = true)
    }

private fun String?.withSamsungPrefix(): String? {
    val name = this.takeIfValidDeviceName() ?: return null
    return if (name.startsWith("Samsung", ignoreCase = true)) name else "Samsung $name"
}

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended). 任何异常都吞掉，返回 "Unknown"。
 */
fun getSELinuxStatusRaw(): String {
    return runCatching {
        val shell = Shell.Builder.create().build("sh")

        val stdoutList = ArrayList<String>()
        val stderrList = ArrayList<String>()
        val result = shell.use {
            it.newJob().add("getenforce").to(stdoutList, stderrList).exec()
        }
        val stdout = stdoutList.joinToString("\n").trim()
        val stderr = stderrList.joinToString("\n").trim()

        if (result.isSuccess) {
            return@runCatching when (stdout) {
                "Enforcing", "Permissive", "Disabled" -> stdout
                else -> "Unknown"
            }
        }

        if (stderr.endsWith("Permission denied")) {
            "Enforcing"
        } else {
            "Unknown"
        }
    }.getOrElse { e ->
        Log.w(TAG, "读取 SELinux 状态失败,按 Unknown 处理: ", e)
        "Unknown"
    }
}
