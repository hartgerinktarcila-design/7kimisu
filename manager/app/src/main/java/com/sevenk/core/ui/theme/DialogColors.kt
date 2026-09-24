package com.sevenk.core.ui.theme

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹窗底色的**不透明版本** —— 所有弹窗都必须用这个。
 *
 * 为什么需要：
 * Miuix 的 `WindowDialog` 底色默认取 `MiuixTheme.colorScheme.background`
 * （上游 `DialogContentLayout` 里的 `DialogDefaults.backgroundColor()`），
 * 而「界面透明」会把 `background` 的 alpha 直接乘成可见度 —— 可见度拉满时
 * alpha 只剩 **0.02**，弹窗等于全透明，文字直接压在壁纸上根本看不清。
 * （有人反馈「公告看不清」就是这个原因。）
 *
 * 所以这里的做法是：**颜色照抄主题，只把 alpha 拉回 1**。
 * 别改成别的颜色 —— 那样弹窗会和主题脱节。
 *
 * 用法：`WindowDialog(..., backgroundColor = miuixDialogColor())`
 */
@Composable
fun miuixDialogColor(): Color = MiuixTheme.colorScheme.background.copy(alpha = 1f)

/**
 * Material 侧同上。
 * Material3 的 `AlertDialog` 默认底色是 `AlertDialogDefaults.containerColor`
 * （即 `surfaceContainerHigh`），同样会被「界面透明」调成半透明，所以也要拉回 1。
 *
 * 用法：`ExpressiveDialog(..., containerColor = materialDialogColor())`
 */
@Composable
fun materialDialogColor(): Color = AlertDialogDefaults.containerColor.copy(alpha = 1f)
