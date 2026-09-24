package com.sevenk.core.ui.component.material

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sevenk.core.ui.component.WarningLevel

/**
 * Material 版「警告卡」—— 与 Miuix 的
 * [com.sevenk.core.ui.component.miuix.WarningCard] 语义一一对应。
 *
 * 历史：这张卡原来**私藏在** `ui/screen/home/HomeMaterial.kt` 里，只有首页能用。
 * v2.2 的 D6 需要在**安装页**也放一张（"检测到当前 root 不是 7kimisu 的内核"），
 * 于是把它提出来放到公共位置，首页改成 import 这一份 —— 保持**全仓只有一份实现**。
 *
 * ⚠️ 底色必须来自主题的 `errorContainer` / `tertiaryContainer`：
 * 「界面透明」那个开关会把主题底色压到接近全透明，卡片必须自己把底色画回来，
 * 否则文字直接压在壁纸上（见 DialogColors.kt）。
 */
@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    level: WarningLevel = WarningLevel.Error,
    onClick: (() -> Unit)? = null,
) {
    val containerColor = when (level) {
        WarningLevel.Error -> MaterialTheme.colorScheme.errorContainer
        WarningLevel.Notice -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.contentColorFor(containerColor)
            )
        }
    }
    if (onClick != null) {
        TonalCard(
            modifier = modifier,
            containerColor = containerColor,
            onClick = onClick,
            content = content,
        )
    } else {
        TonalCard(
            modifier = modifier,
            containerColor = containerColor,
            content = content,
        )
    }
}
