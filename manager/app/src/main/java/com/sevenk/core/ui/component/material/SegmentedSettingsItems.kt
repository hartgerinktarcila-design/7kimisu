package com.sevenk.core.ui.component.material

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sevenk.core.R
import com.sevenk.core.ui.theme.materialDialogColor

/**
 * Material 侧的两类"带交互"设置项 —— 补 Miuix 早就有的能力。
 *
 * 为什么单独一个文件:2026-09-16 的审计发现 **Material 设置页只有 19 项、Miuix 有 48 项**,
 * 整个「个性化」段(Material 侧)从来没建过 → 用户切到 Material 后一堆开关凭空消失。
 * 补这批设置需要两类 Material 控件,而 `SegmentedList.kt` 里只有 switch / dropdown / list,
 * 所以在这里补齐,**不改动那个 651 行的老文件**(降低碰坏现有控件的风险)。
 */

/**
 * 带滑杆的设置项(Material 版)。
 *
 * 与 Miuix 的 `SliderPreference` 语义一致:`value` 一律是 **0f~1f 的归一化值**,
 * 调用方自己负责与真实值(如 0.4×~3.0×)互转 —— 这样两份界面的换算公式只写一处逻辑,
 * 存进 prefs 的也是同一个数。
 */
@Composable
fun SegmentedSliderItem(
    title: String,
    value: Float,
    icon: ImageVector? = null,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    SegmentedListItem(
        enabled = enabled,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, title) } },
        supportingContent = {
            Column {
                if (summary != null) {
                    Text(summary)
                }
                Slider(
                    value = value.coerceIn(0f, 1f),
                    onValueChange = onValueChange,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        },
    )
}

/**
 * 「点一下弹输入框改字符串」的设置项(Material 版,对应 Miuix 的 `StringEditArrow`)。
 *
 * · 摘要显示调用方给的 summary(通常含当前值)
 * · 每次打开都用当前值做初值;点确定才写回
 * · ⚠️ 弹窗必须显式传 `materialDialogColor()`:本项目的「界面透明」会把主题 background
 *   的 alpha 压到 0.02,不传就成了全透明弹窗。
 */
@Composable
fun SegmentedStringItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    summary: String? = null,
    emptySummary: String = "未设置（点一下输入）",
    colors: ListItemColors = defaultSegmentedColors(),
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    SegmentedListItem(
        onClick = { showDialog = true },
        enabled = enabled,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, title) } },
        supportingContent = { Text(summary ?: value.ifBlank { emptySummary }) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        },
    )

    if (showDialog) {
        // 每次打开都拿最新值当草稿(和 Miuix 的 StringEditDialog 行为一致)
        var draft by remember(value) { mutableStateOf(value) }

        ExpressiveDialog(
            onDismissRequest = { showDialog = false },
            containerColor = materialDialogColor(),
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(draft)
                        showDialog = false
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
