package com.sevenk.core.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.LocalMainPagerState
import com.sevenk.core.ui.component.FloatingBottomBar
import com.sevenk.core.ui.component.FloatingBottomBarItem
import com.sevenk.core.ui.theme.LocalEnableFloatingBottomBar
import com.sevenk.core.ui.theme.LocalEnableFloatingBottomBarBlur
import com.sevenk.core.ui.theme.LocalFlatUi
import com.sevenk.core.ui.util.BlurredBar
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomBarMiuix(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier,
) {
    // ⚠️ Natives.isFullFeatured() 是 JNI 调用,组合期抛异常会闪退;失败当 true(功能全开)
    val fullFeatured = runCatching { Natives.isFullFeatured() }.getOrDefault(true)
    if (!fullFeatured) return

    val mainState = LocalMainPagerState.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current

    val items = BottomBarDestination.entries.map { destination ->
        NavigationItem(
            label = stringResource(destination.label),
            icon = destination.icon,
        )
    }
    if (!enableFloatingBottomBar) {
        val barColor = if (blurBackdrop != null || LocalFlatUi.current) Color.Transparent else MiuixTheme.colorScheme.surface
        if (com.sevenk.core.ui.util.NavIcons.hasAny(LocalContext.current)) {
            // 有自定义图标:不能用 Miuix 的 NavigationBar(它只收 ImageVector,位图塞不进去),
            // 换成我们自己的同尺寸实现。没有自定义图标时走下面的原版,外观零变化。
            BlurredBar(blurBackdrop) {
                NavigationBarWithBitmaps(
                    items = items,
                    selectedIndex = mainState.selectedPage,
                    navigationBadge = navigationBadge,
                    onClickIndex = { mainState.animateToPage(it) },
                    barColor = barColor,
                    modifier = modifier,
                )
            }
        } else {
            BlurredBar(blurBackdrop) {
                NavigationBar(
                    modifier = modifier,
                    color = barColor,
                    content = {
                        items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                modifier = Modifier.weight(1f),
                                icon = item.icon,
                                label = item.label,
                                selected = mainState.selectedPage == index,
                                onClick = {
                                    mainState.animateToPage(index)
                                },
                                badge = navigationBadgeFor(index, navigationBadge),
                            )
                        }
                    }
                )
            }
        }
    } else {
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }

        // 每个页签的内容(图标/文字/角标)。
        // 颜色走 LocalContentColor:FloatingBottomBar 的“背景副本”靠它把选中项染成主题色,
        // 扁平化时由外面直接给选中项 primary、其它项 onSurface。
        val tabContent: @Composable (Int, NavigationItem) -> Unit = { index, item ->
            val badge = navigationBadgeFor(index, navigationBadge, floating = true)
            val icon: @Composable () -> Unit = {
                NavIcon(index = index, fallback = item.icon, label = item.label)
            }
            if (badge != null) {
                BadgedBox(badge = { badge() }) { icon() }
            } else {
                icon()
            }
            Text(
                text = item.label,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
        }

        if (LocalFlatUi.current) {
            // 「界面扁平化」:不要胶囊/阴影/液态玻璃,只剩图标和文字;选中项用主题色。
            Row(
                modifier = modifier
                    .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val selected = mainState.selectedPage == index
                    CompositionLocalProvider(
                        LocalContentColor provides if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        }
                    ) {
                        FloatingBottomBarItem(
                            selected = selected,
                            onClick = { mainState.animateToPage(index) },
                            // 扁平化下不再用 FloatingBottomBar 的拖拽动画(它原本顺手把点击也兜了),
                            // 所以这里必须自己接触摸 —— 否则底下这几个页签就点不动了。
                            modifier = Modifier.pointerInput(index) {
                                detectTapGestures { mainState.animateToPage(index) }
                            },
                        ) {
                            tabContent(index, item)
                        }
                    }
                }
            }
        } else {
            FloatingBottomBar(
                modifier = modifier
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
                selectedIndex = mainState.selectedPage,
                onSelected = { mainState.animateToPage(it) },
                backdrop = backdrop,
                tabsCount = items.size,
                isBlurEnabled = enableFloatingBottomBarBlur,
            ) { activateTab ->
                items.forEachIndexed { index, item ->
                    FloatingBottomBarItem(
                        selected = mainState.selectedPage == index,
                        onClick = {
                            activateTab(index)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        tabContent(index, item)
                    }
                }
            }
        }
    }
}

enum class BottomBarDestination(
    @get:StringRes val label: Int,
    val icon: ImageVector,
) {
    Home(R.string.home, Icons.Rounded.Cottage),
    SuperUser(R.string.superuser, Icons.Rounded.Security),
    Module(R.string.module, Icons.Rounded.Extension),
    Setting(R.string.settings, Icons.Rounded.Settings)
}

/**
 * 「不悬浮底栏」的自绘版本 —— 只在存在自定义图标时使用。
 *
 * Miuix 原版 NavigationBar/NavigationBarItem 只接受 ImageVector,位图塞不进去,
 * 所以这里用官方公开的 NavigationBarDefaults(尺寸常量) + MiuixTheme.colorScheme(配色)
 * 重画一遍,尽量和原版一致:
 *   · 高 = NavigationBarDefaults.ItemHeight
 *   · 选中项 = 圆角药丸(surfaceContainer 底色),未选中 = 透明
 *   · 图标/文字色 = onSurfaceContainer,未选中透明度 = NavigationBarDefaults.UnselectedAlpha
 * 没有自定义图标时仍走 Miuix 原版(外观零变化)。
 */
@Composable
private fun NavigationBarWithBitmaps(
    items: List<NavigationItem>,
    selectedIndex: Int,
    navigationBadge: NavigationBadgeState,
    onClickIndex: (Int) -> Unit,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(barColor)
            .height(NavigationBarDefaults.ItemHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = selectedIndex == index
            val contentAlpha = if (selected) 1f else NavigationBarDefaults.UnselectedAlpha
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClickIndex(index) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (selected) scheme.surfaceContainer else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides scheme.onSurfaceContainer.copy(alpha = contentAlpha)
                    ) {
                        val badge = navigationBadgeFor(index, navigationBadge)
                        if (badge != null) {
                            BadgedBox(badge = { badge() }) {
                                NavIcon(index = index, fallback = item.icon, label = item.label)
                            }
                        } else {
                            NavIcon(index = index, fallback = item.icon, label = item.label)
                        }
                    }
                    Text(
                        text = item.label,
                        fontSize = NavigationBarDefaults.LabelFontSize,
                        maxLines = 1,
                        softWrap = false,
                        color = scheme.onSurfaceContainer.copy(alpha = contentAlpha),
                    )
                }
            }
        }
    }
}

/**
 * 一个导航图标:用户自定义过就画那张图,否则用默认的 Material 图标。
 *
 * 图片从私有目录读(NavIcons),存的时候已经压到 256px,这里再按 24dp 需要的像素数采样。
 * 两个开关:圆形裁切(头像好看)、主题色染色(单色剪影用)。
 */
@Composable
private fun NavIcon(
    index: Int,
    fallback: ImageVector,
    label: String,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val rev = com.sevenk.core.ui.util.WallpaperPrefs.observe()
    val prefs = androidx.compose.runtime.remember(rev) {
        val r = com.sevenk.core.data.repository.SettingsRepositoryImpl()
        r.navIconCircle to r.navIconTint
    }
    val circle = prefs.first
    val tint = prefs.second
    val targetPx = with(density) { NavigationBarDefaults.IconSize.roundToPx() }
    val key = com.sevenk.core.ui.util.NavIcons.keys.getOrNull(index)
    val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(
        initialValue = null, context, key, targetPx, com.sevenk.core.ui.util.NavIcons.version
    ) {
        value = if (key == null) {
            null
        } else {
            withContext(Dispatchers.IO) { com.sevenk.core.ui.util.NavIcons.load(context, key, targetPx) }
        }
    }
    val image = bitmap
    if (image == null) {
        Icon(
            imageVector = fallback,
            contentDescription = label,
            // 和 Miuix 导航项一致地卡到 IconSize(26dp),否则自定义/默认切换时图标会跳一下
            modifier = Modifier.size(NavigationBarDefaults.IconSize),
        )
        return
    }
    Image(
        bitmap = image,
        contentDescription = label,
        modifier = Modifier
            .size(NavigationBarDefaults.IconSize)
            .then(if (circle) Modifier.clip(CircleShape) else Modifier),
        contentScale = ContentScale.Crop,
        colorFilter = if (tint) ColorFilter.tint(LocalContentColor.current) else null,
    )
}

internal fun navigationBadgeFor(
    index: Int,
    state: NavigationBadgeState,
    floating: Boolean = false,
): (@Composable () -> Unit)? {
    val badge = badgeFor(index, state) ?: return null
    return when (badge.tone) {
        BadgeTone.Alert -> {
            {
                Badge {
                    Text(badge.count.toString())
                }
            }
        }

        BadgeTone.Accent -> {
            {
                Badge(
                    containerColor = if (floating) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.primary,
                    contentColor = if (floating) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onPrimary,
                ) {
                    Text(badge.count.toString())
                }
            }
        }
    }
}
