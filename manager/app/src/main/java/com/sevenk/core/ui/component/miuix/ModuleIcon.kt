package com.sevenk.core.ui.component.miuix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.ui.theme.miuixDialogColor
import com.sevenk.core.ui.util.ModuleIcons
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 模块图标(模块卡片左边那个)。
 *
 * · 没自定义过 → 显示默认图标(模块名首字 + 柔和底色),右下角一个「+」提示可以换图
 * · 点一下 → 弹「模块图标」设置框:换一张图片 / 自裁这张图 / 恢复默认图标
 * · 选完图会直接进「自裁」框(正方形取景:单指拖动、双指缩放、复位/确定),
 *   确定后把裁好的结果烘培成图标存下来(显示的就是这一张)
 */
@Composable
fun ModuleIcon(
    moduleId: String,
    moduleName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val size = 46.dp
    var dialog by remember { mutableStateOf(false) }

    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleIcons.version) {
        value = withContext(Dispatchers.IO) { ModuleIcons.load(context, moduleId, 128) }
    }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { dialog = true },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "模块图标",
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop,
                )
            } else {
                DefaultModuleIcon(moduleName)
            }
        }
        // 没自定义图时:右下角一个小「+」,提示这里能换图
        if (bitmap == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    if (dialog) {
        ModuleIconDialog(moduleId = moduleId, onDismiss = { dialog = false })
    }
}

/** 默认图标:模块名首字 + 柔和底色(每个模块颜色不同,好区分) */
@Composable
private fun DefaultModuleIcon(moduleName: String) {
    val palette = listOf(
        Color(0xFF7E9CD8), Color(0xFF98BB6C), Color(0xFFE46876),
        Color(0xFFE6C384), Color(0xFF957FB8), Color(0xFF7FB4CA),
    )
    val idx = remember(moduleName) {
        moduleName.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff } % palette.size
    }
    val base = palette[idx]
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(base.copy(alpha = 0.95f), base.copy(alpha = 0.65f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = firstGlyph(moduleName),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 取模块名的首个"字"。
 *
 * 不能用 take(1):那是取 1 个 UTF-16 码元,emoji 这类非 BMP 字符是代理对,
 * 会被截成半个 → 界面上显示成 "�"。所以按**码点**取。
 */
private fun firstGlyph(moduleName: String): String {
    val s = moduleName.trim()
    if (s.isEmpty()) return "模"
    return String(Character.toChars(s.codePointAt(0)))
}

/**
 * 「模块图标」——**只用这一个 WindowDialog**,内容在「菜单」和「自裁」之间切换。
 *
 * 为什么必须这样:之前是"菜单一个 WindowDialog + 自裁另一个 WindowDialog",
 * 切换时旧窗口还没销毁就把触摸全吃了 → 自裁框里按钮点不动、退不出来。
 * 现在只有一个窗口,不存在叠加。
 */
@Composable
private fun ModuleIconDialog(
    moduleId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var cropping by remember { mutableStateOf(false) }
    val hasOriginal = remember(moduleId, ModuleIcons.version) { ModuleIcons.hasOriginal(context, moduleId) }
    val hasIcon = remember(moduleId, ModuleIcons.version) { ModuleIcons.has(context, moduleId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (ModuleIcons.saveOriginal(context, moduleId, uri)) {
                cropping = true
            } else {
                android.widget.Toast.makeText(context, "这张图读不出来,换一张试试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    WindowDialog(
        show = true,
        backgroundColor = miuixDialogColor(),
        title = if (cropping) "自裁图标" else "模块图标",
        // 【必须传】WindowDialog 的 onDismissRequest 默认是 null,Dialog 里是
        // `currentOnDismissRequest.value?.invoke()` —— 不传的话返回键和点击外部
        // 全都不响应。之前就没传,所以一旦底下那排按钮被裁掉(见自裁内容里的说明),
        // 整个弹窗就彻底锁死、退不出来。
        onDismissRequest = onDismiss,
        content = {
            if (cropping) {
                ModuleIconCropContent(
                    moduleId = moduleId,
                    onCancel = onDismiss,
                    onApplied = onDismiss,
                )
            } else {
                Column {
                    Text(
                        text = "给这个模块换一张图标;选完可以拖动/双指缩放自裁。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(
                        text = "换一张图片(并自裁)",
                        onClick = { picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    if (hasOriginal) {
                        TextButton(
                            text = "自裁这张图",
                            onClick = { cropping = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        text = "恢复默认图标",
                        onClick = {
                            ModuleIcons.clear(context, moduleId)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasIcon,
                    )
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/** 自裁内容(正方形取景):预览用 Canvas 按 ModuleIcons.cropRect 画,和烘培是同一套数学 */
@Composable
private fun ModuleIconCropContent(
    moduleId: String,
    onCancel: () -> Unit,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleIcons.version) {
        value = withContext(Dispatchers.IO) { ModuleIcons.loadOriginal(context, moduleId) }
    }
    val saved = remember(moduleId) { ModuleIcons.loadCrop(context, moduleId) }
    var biasX by remember { mutableFloatStateOf(saved?.first ?: 0f) }
    var biasY by remember { mutableFloatStateOf(saved?.second ?: 0f) }
    var zoom by remember { mutableFloatStateOf(saved?.third ?: 1f) }

    // 取景框必须"看菜下饭":WindowDialog 的内容区**没有滚动**
    // (上游 DialogContentLayout 只有 heightIn(max=…),没有 verticalScroll),
    // 超出 maxHeight 的内容会被直接裁掉、滚都滚不动。
    // 这里早先写的是 fillMaxWidth(0.72f) + aspectRatio(1f) —— 那个正方形会把
    // 全部高度预算吃掉,把下面那排「复位/取消/确定」顶出屏幕,表现就是
    // "自裁框里没有确定键、退不出来"(屏幕越高越明显,因为方块越大)。
    // 现在按**真实可用高度**算边长,并给下面三行(提示文字 + 两组按钮)留出 150dp。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val budget = if (maxHeight.value.isFinite()) maxHeight - 150.dp else 260.dp
        val side = minOf(maxWidth * 0.72f, budget, 260.dp).coerceAtLeast(96.dp)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            biasX = (biasX - pan.x / w * 2f).coerceIn(-1f, 1f)
                            biasY = (biasY - pan.y / h * 2f).coerceIn(-1f, 1f)
                            zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                        }
                    },
            ) {
                val b = bitmap
                if (b != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val r = ModuleIcons.cropRect(b.width, b.height, biasX, biasY, zoom)
                        drawImage(
                            image = b,
                            srcOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                            srcSize = IntSize(
                                r.width().toInt().coerceAtLeast(1),
                                r.height().toInt().coerceAtLeast(1),
                            ),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(
                                size.width.toInt().coerceAtLeast(1),
                                size.height.toInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            Text(
                text = "拖动调位置 · 双指缩放 · 现在是 ${"%.1f".format(zoom)}×",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "缩小",
                    onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                    modifier = Modifier.weight(1f),
                    enabled = zoom > 1f,
                )
                TextButton(
                    text = "放大",
                    onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                    modifier = Modifier.weight(1f),
                    enabled = zoom < 4f,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "复位",
                    onClick = {
                        biasX = 0f
                        biasY = 0f
                        zoom = 1f
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确定",
                    onClick = {
                        if (ModuleIcons.bake(context, moduleId, biasX, biasY, zoom)) {
                            onApplied()
                        } else {
                            android.widget.Toast.makeText(context, "保存失败,重新选一张图试试", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
