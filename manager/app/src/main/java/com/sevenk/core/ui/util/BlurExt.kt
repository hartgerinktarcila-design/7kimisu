package com.sevenk.core.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.sevenk.core.ui.theme.LocalFlatUi
import com.sevenk.core.ui.theme.LocalImmersiveBars
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 有壁纸 + 开了「界面透明」时:顶栏/底栏不再画自己的遮罩,也不做模糊。
    // 这样栏区域显示的就是 Scaffold 的半透明 surface,和页面背景完全一致,
    // 壁纸一路连续透过去,不会出现一条深色/死黑的横条。
    // (标题、返回、页签等内容与交互全部保留,只是“背景”没了。)
    //
    // 开了「界面扁平化」时同样不画:否则顶栏会留下一道模糊色带 + 一条硬边,
    // 在纯壁纸背景上看起来就是一条多余的“边框”。
    if (LocalImmersiveBars.current || LocalFlatUi.current) {
        Box { content() }
        return
    }

    val surfaceColor = MiuixTheme.colorScheme.surface

    // 有壁纸时:surfaceColor 本身已经是半透明的(主题注入过 alpha),
    // 这里就沿用它自己的 alpha,让顶栏/底栏也透出"模糊后的壁纸",
    // 而不是变成一块死黑。
    // 没壁纸时保持原来的观感(0.87 不透明度)。
    val blendColor = if (surfaceColor.alpha < 1f) {
        surfaceColor.copy(alpha = (surfaceColor.alpha * 0.95f).coerceIn(0.06f, 1f))
    } else {
        surfaceColor.copy(0.87f)
    }

    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(BlendColorEntry(color = blendColor)),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}

/**
 * Miuix 顶栏的底色。
 *
 * 原来各页面都写 `if (blurActive) Color.Transparent else colorScheme.surface`:
 * 模糊不可用时顶栏会退化成一条不透明 surface 色带 —— 在「界面扁平化」下就是一条
 * 多余的边框。所以这里统一收口:扁平化时永远透明。
 */
@Composable
fun miuixBarColor(blurActive: Boolean): Color =
    if (blurActive || LocalFlatUi.current) Color.Transparent else MiuixTheme.colorScheme.surface
