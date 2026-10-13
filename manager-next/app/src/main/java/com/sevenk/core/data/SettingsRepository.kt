package com.sevenk.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.theme.ColorMode

private const val SETTINGS_PREFS = "settings"

/**
 * 首版的设置仓库：手写 SharedPreferences 封装，无 DI。
 * 只保留首版功能用到的键；键名与旧版一致，老用户升级后设置不丢。
 */
class SettingsRepository {

    private val prefs: SharedPreferences by lazy {
        ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    }

    /** 主题颜色模式：[ColorMode] 的 value（0 跟随系统 / 1 浅色 / 2 深色） */
    var themeMode: Int
        get() = prefs.getInt("color_mode", 0)
        set(value) = prefs.edit { putInt("color_mode", value) }

    /** 隐身模式的拨号密令数字(如 70707 对应 *#*#70707#*#*) */
    var stealthCode: String
        get() = prefs.getString("stealth_code", "70707") ?: "70707"
        set(value) = prefs.edit { putString("stealth_code", value) }

    /** 超级用户页：是否显示系统应用 */
    var superuserShowSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit { putBoolean("show_system_apps", value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
