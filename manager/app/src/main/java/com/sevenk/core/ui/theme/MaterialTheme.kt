package com.sevenk.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.webui.MonetColorsProvider

@Composable
fun MaterialKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.colorMode.isAmoled
    val dynamicColor = appSettings.keyColor == 0

    val colorScheme = rememberKernelSUColorScheme(
        seedColor = if (dynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoledMode,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // 有壁纸且开了"界面透明"时,把大面积的容器色变半透明,让壁纸透出来。
    // 读 WallpaperPrefs.observe() 保证壁纸设置一变这个主题就会重组。
    // ⚠️ v0.13.157:隐身时**照常半透明**(原来这里有 `!StealthLook.isPlain()` 的"素颜"判断,已撤)。
    val wallpaperRev = com.sevenk.core.ui.util.WallpaperPrefs.observe()
    // 分朝向:横屏没单独设壁纸时用的是竖屏那张 —— 判断要按当前朝向,转屏时重算
    val wallpaperSlot = com.sevenk.core.ui.util.rememberWallpaperSlot()
    val wallpaperActive = remember(wallpaperRev, wallpaperSlot) {
        val r = SettingsRepositoryImpl()
        com.sevenk.core.ui.util.WallpaperStore.hasWallpaper(wallpaperSlot) && r.uiTranslucent
    }
    val surfaceAlpha = remember(wallpaperRev, wallpaperSlot) {
        if (!wallpaperActive) 1f
        else SettingsRepositoryImpl().uiTranslucentAlpha.coerceIn(0.02f, 1f)
    }

    val animatedColorScheme = colorScheme.animateAsState().let {
        if (surfaceAlpha >= 1f) it else it.translucent(surfaceAlpha)
    }

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        motionScheme = MotionScheme.expressive(),
        content = {
            MonetColorsProvider.UpdateCss(colorScheme)
            content()
        }
    )
}


/** 把大面积的容器色变成半透明(小于 1 才处理) */
private fun ColorScheme.translucent(alpha: Float): ColorScheme {
    // 页面背景用给定的透明度;卡片/容器更实一点,保证上面的字还看得清
    // 卡片比页面背景"实"一点,保证字看得清;但别太实,否则壁纸透不过来。
    // 0.45 → 0.28:同样可见度下卡片更通透(可见度拉满时 0.505 → 0.35)。
    val card = (alpha + (1f - alpha) * 0.28f).coerceAtMost(1f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceDim = surfaceDim.copy(alpha = alpha),
        surfaceBright = surfaceBright.copy(alpha = alpha),
        surfaceVariant = surfaceVariant.copy(alpha = card),
        surfaceContainer = surfaceContainer.copy(alpha = card),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = card),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = card),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = card),
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = card),
    )
}
