package com.sevenk.core.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.sevenk.core.R
import com.sevenk.core.ui.component.markdown.MarkdownContent
import com.sevenk.core.ui.theme.miuixDialogColor
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun LoadingDialogMiuix(
    showDialog: MutableState<Boolean>,
) {
    WindowDialog(
        show = showDialog.value,
        // 弹窗底色必须不透明,否则"界面透明"时弹窗几乎全透明、字压在壁纸上(见 DialogColors.kt)
        backgroundColor = miuixDialogColor(),
        content = {
            // Consume the back gesture before the dialog's own handler
            val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(
                state = navEventState,
                isBackEnabled = true,
                onBackCompleted = { },
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    InfiniteProgressIndicator(
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Text(
                        modifier = Modifier.padding(start = 12.dp),
                        text = stringResource(R.string.processing),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    )
}

@Composable
fun ConfirmDialogMiuix(
    visuals: ConfirmDialogVisuals,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    showDialog: MutableState<Boolean>
) {
    WindowDialog(
        show = showDialog.value,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        backgroundColor = miuixDialogColor(),
        title = visuals.title,
        onDismissRequest = {
            dismiss()
            showDialog.value = false
        },
        content = {
            Layout(
                content = {
                    val dismissState = LocalDismissState.current
                    visuals.content?.let { content ->
                        when {
                            visuals.isMarkdown -> MarkdownContent(content = content, isMarkdown = true)
                            visuals.isHtml -> MarkdownContent(content = content, isMarkdown = false)
                            else -> Text(text = content)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        TextButton(
                            text = visuals.dismiss ?: stringResource(id = android.R.string.cancel),
                            onClick = {
                                dismiss()
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = visuals.confirm ?: stringResource(id = android.R.string.ok),
                            onClick = {
                                confirm()
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            ) { measurables, constraints ->
                if (measurables.size != 2) {
                    val button = measurables[0].measure(constraints)
                    layout(constraints.maxWidth, button.height) {
                        button.place(0, 0)
                    }
                } else {
                    val button = measurables[1].measure(constraints)
                    val lazyList = measurables[0].measure(constraints.copy(maxHeight = constraints.maxHeight - button.height))
                    layout(constraints.maxWidth, lazyList.height + button.height) {
                        lazyList.place(0, 0)
                        button.place(0, lazyList.height)
                    }
                }
            }
        }
    )
}

/**
 * 「只读告知」弹窗（Miuix 版）—— 只有一个「知道了」按钮。
 * 与 [InfoDialogMaterial] 语义一致，理由见那边的注释。
 *
 * 布局沿用 [ConfirmDialogMiuix] 那套自定义 `Layout`：它把正文和按钮分开测量，
 * 正文超长时能自己滚动，不会把按钮挤出屏幕（这是本项目踩过的坑）。
 */
@Composable
fun InfoDialogMiuix(
    show: Boolean,
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        backgroundColor = miuixDialogColor(),
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Layout(
                content = {
                    Text(text = content)
                    val dismissState = LocalDismissState.current
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        TextButton(
                            text = stringResource(id = android.R.string.ok),
                            onClick = {
                                onDismiss()
                                dismissState?.invoke()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            ) { measurables, constraints ->
                if (measurables.size != 2) {
                    val button = measurables[0].measure(constraints)
                    layout(constraints.maxWidth, button.height) {
                        button.place(0, 0)
                    }
                } else {
                    val button = measurables[1].measure(constraints)
                    val body = measurables[0].measure(
                        constraints.copy(maxHeight = constraints.maxHeight - button.height)
                    )
                    layout(constraints.maxWidth, body.height + button.height) {
                        body.place(0, 0)
                        button.place(0, body.height)
                    }
                }
            }
        },
    )
}
