package com.sevenk.core.ui.component.material

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.theme.materialDialogColor
import com.sevenk.core.ui.util.NavIcons
import com.sevenk.core.ui.util.StatusDecoration
import com.sevenk.core.ui.util.StatusDecorationImage
import com.sevenk.core.ui.util.WallpaperPrefs

/**
 * 个性化(美化类)里两个"带画面"的零件 —— Miuix 侧这两个是 SettingsMiuix.kt 里的私有函数,
 * Material 侧以前**完全没有**(整个个性化段都没建)。2026-09-16 第二批补齐时搬过来,
 * 保证两份界面的取景数学、渲染函数、配置键都只有一处定义。
 */

/**
 * 导航图标设置行右侧的小预览:显示当前选好的那张图(圆形/着色与导航栏一致)。
 *
 * 读 [WallpaperPrefs.observe] 是为了「导航图标裁成圆形」一拨开关预览就跟着变
 * —— 那个开关写的是 prefs,不订阅的话这里不会重组。
 */
@Composable
internal fun NavIconPreview(key: String) {
    val context = LocalContext.current
    val rev = WallpaperPrefs.observe()
    val circle = remember(rev) { SettingsRepositoryImpl().navIconCircle }
    val bitmap by produceState<ImageBitmap?>(null, context, key, NavIcons.version) {
        value = withContext(Dispatchers.IO) { NavIcons.load(context, key, 96) }
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier
            .size(28.dp)
            .then(if (circle) Modifier.clip(CircleShape) else Modifier),
        contentScale = ContentScale.Crop,
    )
}

/**
 * 「自裁取景」弹窗(Material 版):把卡片比例的一块区域当取景框,拖动/双指缩放调整。
 *
 * 预览用的是首页同一份渲染([StatusDecorationImage]),所以所见即所得;
 * 卡片比例也必须和首页用同两个常量([StatusDecoration.CARD_SIDE_INSET_DP] / [StatusDecoration.CARD_HEIGHT_DP]),
 * 否则取景框比例对不上。
 *
 * ⚠️ 显式传 `materialDialogColor()`:本项目的「界面透明」会把主题 background 的 alpha
 * 压到 0.02,不传就成了全透明弹窗。
 */
@Composable
internal fun DecorationCropDialog(
    onDismiss: () -> Unit,
    onApply: (biasX: Float, biasY: Float, zoom: Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, context, StatusDecoration.version, screenWidthPx) {
        value = withContext(Dispatchers.IO) { StatusDecoration.load(context, screenWidthPx) }
    }
    val repo = remember { SettingsRepositoryImpl() }
    var biasX by remember { mutableFloatStateOf(repo.statusDecorationBiasX) }
    var biasY by remember { mutableFloatStateOf(repo.statusDecorationBiasY) }
    var zoom by remember { mutableFloatStateOf(repo.statusDecorationZoom) }
    // 卡片 = (屏宽 − 左右各 12dp) × CARD_HEIGHT_DP,这里按同一比例开取景框。
    // 必须和首页的 sideInset 用同一个常量,否则取景框比例对不上、就不是所见即所得了。
    val cardAspect = (config.screenWidthDp - 2 * StatusDecoration.CARD_SIDE_INSET_DP) /
        StatusDecoration.CARD_HEIGHT_DP

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        containerColor = materialDialogColor(),
        title = { Text("自裁取景") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(cardAspect)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                // 手指往哪边拖,就露出图片哪边(所以偏移是减)
                                biasX = (biasX - pan.x / w * 2f).coerceIn(-1f, 1f)
                                biasY = (biasY - pan.y / h * 2f).coerceIn(-1f, 1f)
                                zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            }
                        },
                ) {
                    val image = bitmap
                    if (image != null) {
                        StatusDecorationImage(
                            bitmap = image,
                            biasX = biasX,
                            biasY = biasY,
                            zoom = zoom,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Text(
                    text = "拖动调位置 · 双指缩放 · 现在是 ${"%.1f".format(zoom)}×",
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 显式缩放按钮:平板/单指场景比捏合好使
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                        modifier = Modifier.weight(1f),
                        enabled = zoom > 1f,
                    ) { Text("缩小") }
                    TextButton(
                        onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                        modifier = Modifier.weight(1f),
                        enabled = zoom < 4f,
                    ) { Text("放大") }
                    TextButton(
                        onClick = {
                            biasX = 0f
                            biasY = 0f
                            zoom = 1f
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("复位") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(biasX, biasY, zoom) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
