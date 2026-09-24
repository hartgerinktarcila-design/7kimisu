package com.sevenk.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.sevenk.core.data.repository.SettingsRepository
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode

enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    val isSystem: Boolean get() = value == 0 || value == 3
    val isDark: Boolean get() = value == 2 || value == 5 || value == 6
    val isAmoled: Boolean get() = value == 6
    val isMonet: Boolean get() = value >= 3

    fun toNonMonetMode(): Int = when (this) {
        MONET_SYSTEM -> 0
        MONET_LIGHT -> 1
        MONET_DARK, DARK_AMOLED -> 2
        else -> value
    }

    fun toMonetMode(): Int = when (this) {
        SYSTEM -> 3
        LIGHT -> 4
        DARK -> 5
        else -> value
    }
}

data class AppSettings(
    val colorMode: ColorMode,
    val keyColor: Int,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion,
)

val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
            this == PaletteStyle.Neutral ||
            this == PaletteStyle.Vibrant ||
            this == PaletteStyle.Expressive

fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

object ThemeController {
    fun getAppSettings(repo: SettingsRepository = SettingsRepositoryImpl()): AppSettings {
        // ⚠️ v0.13.157:隐身时**不再套默认主题**。
        //    原来这里有一段 `if (StealthLook.isPlain()) return 默认配色` —— 已撤:
        //    主题配色(含壁纸主色)不是 root 特征,没 root 的用户也有自己选的配色。
        //    隐身只改"显示什么内容",不改外观。
        val uiMode = repo.uiMode
        var colorModeValue = repo.themeMode

        if (uiMode == "miuix") {
            val miuixMonet = repo.miuixMonet
            val colorMode = ColorMode.fromValue(colorModeValue)
            colorModeValue = if (!miuixMonet && colorMode.isMonet) {
                colorMode.toNonMonetMode()
            } else if (miuixMonet && !colorMode.isMonet) {
                colorMode.toMonetMode()
            } else {
                colorModeValue
            }
        }

        val colorMode = ColorMode.fromValue(colorModeValue)

        // 「用壁纸主色」:开启且有壁纸时,用从壁纸里提取出来的主色当种子色
        val keyColor = if (repo.wallpaperSeed && repo.wallpaperKind == "image" &&
            repo.wallpaperSeedColor != 0
        ) {
            repo.wallpaperSeedColor
        } else {
            repo.keyColor
        }
        val paletteStyleStr = repo.colorStyle
        val paletteStyle = try {
            PaletteStyle.valueOf(paletteStyleStr)
        } catch (_: Exception) {
            PaletteStyle.TonalSpot
        }
        val colorSpecStr = repo.colorSpec
        val colorSpec = try {
            ColorSpec.SpecVersion.valueOf(colorSpecStr)
        } catch (_: Exception) {
            ColorSpec.SpecVersion.SPEC_2025
        }

        return AppSettings(colorMode, keyColor, paletteStyle, colorSpec)
    }
}

@Composable
fun KernelSUTheme(
    appSettings: AppSettings = ThemeController.getAppSettings(),
    uiMode: UiMode = LocalUiMode.current,
    content: @Composable () -> Unit
) {

    when (uiMode) {
        UiMode.Miuix -> MiuixKernelSUTheme(
            appSettings = appSettings,
            content = content
        )

        UiMode.Material -> MaterialKernelSUTheme(
            appSettings = appSettings,
            content = content
        )
    }
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1, 4 -> false  // Force light mode
        2, 5, 6 -> true   // Force dark mode
        else -> isSystemInDarkTheme()  // Follow system (0 or default)
    }
}

val LocalColorMode = staticCompositionLocalOf { 0 }

val LocalEnableBlur = staticCompositionLocalOf { false }

val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/**
 * 有背景壁纸、且开了「界面透明」时为 true。
 *
 * 顶栏/底栏据此不再绘制自己的模糊遮罩（见 util/BlurExt.kt 的 BlurredBar）：
 * 那样栏区域显示的就是 Scaffold 的半透明 surface，和页面背景完全一致，
 * 壁纸可以一路连续透过去，不会出现一条深色横条。
 *
 * 注意：只影响“背景”，标题/返回/页签等内容与交互一律保留。
 */
val LocalImmersiveBars = staticCompositionLocalOf { false }

/**
 * 「界面扁平化」开关(设置里的 flat_home,默认开)的运行时状态。
 *
 * 为 true 时:顶栏、底栏不再画自己的底色/模糊遮罩/选中药丸,页面卡片也不画底色 ——
 * 界面上只剩文字和图标,直接浮在壁纸上。模块页的方框保留(要拿来做美化)。
 *
 * 见 util/BlurExt.kt 的 BlurredBar / miuixBarColor 与 component/bottombar/BottomBarMiuix.kt。
 */
val LocalFlatUi = staticCompositionLocalOf { false }
