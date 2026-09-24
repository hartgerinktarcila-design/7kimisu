package com.sevenk.core.ui.component

import androidx.compose.runtime.Composable
import com.sevenk.core.Natives

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    // 🟠（v2.19）组合期裸调 JNI：native 库没加载/内核接口失效时 `isManager`/`version`
    // 会直接抛 Throwable，异常沿组合往上冒 = 界面整片闪退。改成安全取值，读不到就当"不是管理器"。
    val isManager = runCatching { Natives.isManager }.getOrDefault(false)
    val ksuVersion = if (isManager) runCatching { Natives.version }.getOrNull() else null

    if (ksuVersion != null) {
        content()
    }
}
