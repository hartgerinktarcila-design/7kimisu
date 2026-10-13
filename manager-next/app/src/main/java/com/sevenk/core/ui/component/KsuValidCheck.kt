package com.sevenk.core.ui.component

import androidx.compose.runtime.Composable
import com.sevenk.core.Natives

/**
 * 只有「内核认主且能读到版本号」才渲染内容。
 * 组合期裸调 JNI 可能抛 Throwable，全部 runCatching 兜底成"不是管理器"。
 */
@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    val isManager = runCatching { Natives.isManager }.getOrDefault(false)
    val ksuVersion = if (isManager) runCatching { Natives.version }.getOrNull() else null

    if (ksuVersion != null) {
        content()
    }
}
