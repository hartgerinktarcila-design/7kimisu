package com.sevenk.core.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sevenk.core.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 「点一下弹输入框改字符串」的设置项(对应 SuperEditArrow,但用于文本)。
 *
 * · 摘要直接显示当前值;空值时显示 emptySummary
 * · 每次打开对话框都用当前值做初值;点确定才写回
 */
@Composable
fun StringEditArrow(
    title: String,
    value: String,
    summary: String? = null,
    emptySummary: String = "未设置（点一下输入）",
    startAction: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    ArrowPreference(
        title = title,
        summary = summary ?: value.ifBlank { emptySummary },
        startAction = startAction,
        onClick = { showDialog = true },
        holdDownState = showDialog,
        enabled = enabled,
    )

    StringEditDialog(
        title = title,
        show = showDialog,
        initialValue = value,
        onDismissRequest = { showDialog = false },
        onConfirm = onValueChange,
    )
}

@Composable
private fun StringEditDialog(
    title: String,
    show: Boolean,
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // show / 初值变化时重置草稿,保证每次打开都是最新值
    var draft by remember(show, initialValue) { mutableStateOf(initialValue) }

    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
        content = {
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = draft,
                maxLines = 1,
                onValueChange = { draft = it },
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        onConfirm(draft)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
