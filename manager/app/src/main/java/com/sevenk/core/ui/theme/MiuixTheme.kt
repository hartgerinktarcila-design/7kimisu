package com.sevenk.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.sevenk.core.ui.webui.MonetColorsProvider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun MiuixKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec.effectiveFor(colorStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    val resolvedKeyColor: Color? = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        appSettings.colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> null
    }

    val controller = ThemeController(
        when (appSettings.colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = resolvedKeyColor,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    MiuixTheme(
        controller = controller,
        content = {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MonetColorsProvider.UpdateCss()
            // 有壁纸且开了"界面透明"时,把 Miuix 的大面积容器色变半透明
            // ⚠️ v0.13.157:隐身时**照常半透明**(原来这里有 `!StealthLook.isPlain()` 的"素颜"判断,已撤)。
            val wallpaperRev = com.sevenk.core.ui.util.WallpaperPrefs.observe()
            // 分朝向:横屏没单独设壁纸时用的是竖屏那张 —— 判断要按当前朝向,转屏时重算
            val wallpaperSlot = com.sevenk.core.ui.util.rememberWallpaperSlot()
            val surfaceAlpha = androidx.compose.runtime.remember(wallpaperRev, wallpaperSlot) {
                val r = com.sevenk.core.data.repository.SettingsRepositoryImpl()
                if (com.sevenk.core.ui.util.WallpaperStore.hasWallpaper(wallpaperSlot) && r.uiTranslucent) {
                    r.uiTranslucentAlpha.coerceIn(0.02f, 1f)
                } else 1f
            }
            val scheme = MiuixTheme.colorScheme

            // Miuix 有公开重载 MiuixTheme(colorScheme: Colors, ...),
            // 直接把半透明版本传进去即可 —— 不需要反射,R8 也混淆不掉。
            if (surfaceAlpha < 1f) {
                MiuixTheme(scheme.translucent(surfaceAlpha)) {
                    CompositionLocalProvider(
                        LocalContentColor provides scheme.onBackground,
                        // 有壁纸:让顶栏/底栏融进壁纸(不画自己的遮罩)
                        LocalImmersiveBars provides true,
                    ) {
                        content()
                    }
                }
            } else {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onBackground,
                ) {
                    content()
                }
            }
        }
    )
}


/** 把 Miuix 的大面积容器色变成半透明(卡片比页面背景更实一点,保证可读) */
private fun Colors.translucent(alpha: Float): Colors {
    // 卡片比页面背景"实"一点,保证字看得清;但别太实,否则壁纸透不过来。
    // 0.45 → 0.28:同样可见度下卡片更通透(可见度拉满时 0.505 → 0.35)。
    val card = (alpha + (1f - alpha) * 0.28f).coerceAtMost(1f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceVariant = surfaceVariant.copy(alpha = card),
        surfaceContainer = surfaceContainer.copy(alpha = card),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = card),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = card),
    )
}
