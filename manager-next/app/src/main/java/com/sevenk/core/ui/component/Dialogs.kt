package com.sevenk.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 通用确认弹窗（Miuix WindowDialog 封装）。
 * 不传 onDismissRequest 时返回键/点击外部不响应，这里显式传 onDismiss。
 */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    content: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    WindowDialog(
        show = true,
        title = title,
        summary = content,
        onDismissRequest = onDismiss,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = dismiss,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = confirm,
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}

/**
 * 数字输入弹窗（隐身密令修改用）。
 */
@Composable
fun InputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    confirm: String,
    dismiss: String,
    digitsOnly: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var value by remember(show) { mutableStateOf(initialValue) }
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column {
                TextField(
                    value = value,
                    onValueChange = { raw ->
                        value = if (digitsOnly) raw.filter { it.isDigit() }.take(12) else raw
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true,
                    keyboardOptions = if (digitsOnly) {
                        KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    } else {
                        KeyboardOptions.Default
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = dismiss,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = confirm,
                        onClick = {
                            onConfirm(value)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
