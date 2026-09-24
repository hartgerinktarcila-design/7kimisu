package com.sevenk.core.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sevenk.core.ui.component.markdown.MarkdownContent
import com.sevenk.core.ui.component.material.ExpressiveDialog
import com.sevenk.core.ui.theme.materialDialogColor

@Composable
fun LoadingDialogMaterial(
    showDialog: MutableState<Boolean>,
) {
    if (showDialog.value) {
        Dialog(
            onDismissRequest = { },
            // Keep the dialog non-dismissible
            properties = DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            )
        ) {
            Surface(
                modifier = Modifier.size(100.dp), shape = MaterialTheme.shapes.extraLarge,
                color = materialDialogColor(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }
        }
    }
}

@Composable
fun ConfirmDialogMaterial(
    visuals: ConfirmDialogVisuals,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    showDialog: MutableState<Boolean>
) {
    if (showDialog.value) {
        ExpressiveDialog(
            onDismissRequest = {
                dismiss()
                showDialog.value = false
            },
            containerColor = materialDialogColor(),
            title = { Text(visuals.title) },
            text = visuals.content?.let { content ->
                {
                    when {
                        visuals.isMarkdown -> MarkdownContent(content = content, isMarkdown = true)
                        visuals.isHtml -> MarkdownContent(content = content, isMarkdown = false)
                        else -> Text(text = content)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm()
                        showDialog.value = false
                    }
                ) {
                    Text(visuals.confirm ?: stringResource(id = android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismiss()
                        showDialog.value = false
                    }
                ) {
                    Text(visuals.dismiss ?: stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 「只读告知」弹窗（Material 版）—— 只有一个「知道了」按钮。
 *
 * 为什么不复用 `ConfirmDialogMaterial`：那个一定会画两个按钮
 * （`dismiss` 传 null 时也会退化成「取消」），拿来做纯告知会出现两个按钮干同一件事、
 * 用户不知道该点哪个。所以单独做一个单按钮版本。
 *
 * ⚠️ 底色必须显式拉回不透明（`materialDialogColor()`）——
 * 「界面透明」会把主题底色压到近乎全透明，弹窗变成字压在壁纸上（见 DialogColors.kt）。
 */
@Composable
fun InfoDialogMaterial(
    show: Boolean,
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    if (!show) return
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        containerColor = materialDialogColor(),
        title = { Text(title) },
        text = { Text(content) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
    )
}
