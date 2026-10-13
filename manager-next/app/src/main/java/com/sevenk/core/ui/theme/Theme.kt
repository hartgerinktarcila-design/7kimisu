package com.sevenk.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题颜色模式。首版只有三档（跟随系统/浅色/深色）；
 * TODO: 调色板（莫奈取色/种子色/风格）后置，prefs 键沿用旧版 color_mode。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    val isSystem: Boolean get() = this == SYSTEM
    val isDark: Boolean get() = this == DARK
}

/** 当前生效的颜色模式（由 MainActivity 提供，prefs 改动实时生效） */
val LocalColorMode = staticCompositionLocalOf { ColorMode.SYSTEM.value }

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun KernelSUTheme(
    colorMode: ColorMode,
    content: @Composable () -> Unit
) {
    val darkTheme = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())
    val controller = ThemeController(
        when (colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
        },
        isDark = darkTheme,
    )

    MiuixTheme(controller = controller) {
        content()
    }
}
