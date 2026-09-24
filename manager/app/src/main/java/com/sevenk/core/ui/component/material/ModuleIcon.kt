package com.sevenk.core.ui.component.material

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.ui.theme.materialDialogColor
import com.sevenk.core.ui.util.ModuleIcons

/**
 * 模块图标(Material 界面版)—— 模块卡片左边那个。
 *
 * · 没自定义过 → 显示默认图标(模块名首字 + 柔和底色),右下角一个「+」提示可以换图
 * · 点一下 → 弹「模块图标」框:换一张图片 / 自裁这张图 / 恢复默认图标
 * · 选完图直接进「自裁」框(正方形取景:单指拖动、双指缩放、复位/确定),
 *   确定后把裁好的结果烘培成图标存下来
 *
 * 与 miuix 包的 ModuleIcon 是**同一套行为、不同的皮**:
 * 取景数学与存储全部复用 [ModuleIcons](只有一份 cropRect,预览与烘培共用),
 * 所以不会出现"在 Miuix 里裁好、切到 Material 显示不一样"。
 *
 * ⚠️ 只用**一个** ExpressiveDialog,标题和内容在「菜单」/「自裁」之间切换:
 * v0.13.56 就是因为菜单和自裁是两个窗口,切换时旧窗口还在做退场动画、
 * 把触摸全吃掉,导致自裁框"看得见却点不动"。别再改回两个窗口。
 */
@Composable
fun ModuleIcon(
    moduleId: String,
    moduleName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val iconSize = 46.dp
    var dialog by remember { mutableStateOf(false) }

    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleIcons.version) {
        value = withContext(Dispatchers.IO) { ModuleIcons.load(context, moduleId, 128) }
    }

    Box(modifier = modifier.size(iconSize)) {
        Box(
            modifier = Modifier
                .size(iconSize)
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
                    modifier = Modifier.size(iconSize),
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
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
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
 * 「模块图标」——**只用这一个弹窗**,内容在「菜单」和「自裁」之间切换(见文件头注释)。
 *
 * 自裁参数(biasX/biasY/zoom)提升到这一层持有:因为 ExpressiveDialog 的
 * `text`(取景控件)和 `confirmButton`(确定)是两个平级槽位,
 * 平级槽位之间没法直接传状态,只能由共同父级持有 —— 而不是用全局变量。
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

    // 上次自裁的参数当初始值(重新自裁时接着上次的位置)
    val saved = remember(moduleId) { ModuleIcons.loadCrop(context, moduleId) }
    var biasX by remember(moduleId) { mutableFloatStateOf(saved?.first ?: 0f) }
    var biasY by remember(moduleId) { mutableFloatStateOf(saved?.second ?: 0f) }
    var zoom by remember(moduleId) { mutableFloatStateOf(saved?.third ?: 1f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (ModuleIcons.saveOriginal(context, moduleId, uri)) {
                // 换了新图:自裁参数复位,免得套用上一张图的取景
                biasX = 0f
                biasY = 0f
                zoom = 1f
                cropping = true
            } else {
                android.widget.Toast.makeText(context, "这张图读不出来,换一张试试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        containerColor = materialDialogColor(),
        title = { Text(if (cropping) "自裁图标" else "模块图标") },
        text = {
            if (cropping) {
                ModuleIconCropContent(
                    moduleId = moduleId,
                    biasX = biasX,
                    biasY = biasY,
                    zoom = zoom,
                    onTransform = { dx, dy, dz ->
                        val nextZoom = (zoom * dz).coerceIn(1f, 4f)
                        // 平移换算用的窗口宽度在控件内部算,这里只接已经算好的增量
                        biasX = (biasX + dx).coerceIn(-1f, 1f)
                        biasY = (biasY + dy).coerceIn(-1f, 1f)
                        zoom = nextZoom
                    },
                    onZoomStep = { delta -> zoom = (zoom + delta).coerceIn(1f, 4f) },
                    onReset = {
                        biasX = 0f
                        biasY = 0f
                        zoom = 1f
                    },
                )
            } else {
                Column {
                    Text(
                        text = "给这个模块换一张图标;选完可以拖动/双指缩放自裁。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (hasOriginal) {
                        TextButton(
                            onClick = { cropping = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("自裁这张图")
                        }
                    }
                    TextButton(
                        onClick = {
                            ModuleIcons.clear(context, moduleId)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasIcon,
                    ) {
                        Text("恢复默认图标")
                    }
                }
            }
        },
        confirmButton = {
            if (cropping) {
                TextButton(
                    onClick = {
                        if (ModuleIcons.bake(context, moduleId, biasX, biasY, zoom)) {
                            onDismiss()
                        } else {
                            android.widget.Toast.makeText(context, "保存失败,重新选一张图试试", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text("确定")
                }
            } else {
                TextButton(onClick = { picker.launch("image/*") }) {
                    Text("换一张图片")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 自裁控件(正方形取景)。预览用 Canvas 按 [ModuleIcons.cropRect] 画源图,
 * 与烘培共用**同一份取景数学** → 真正所见即所得。
 *
 * 无状态:取景参数由 [ModuleIconDialog] 持有并通过回调更新。
 *
 * @param onTransform 拖动/双指缩放的回调,参数是**已经归一化**的位移(-1~1)与缩放倍数
 */
@Composable
private fun ModuleIconCropContent(
    moduleId: String,
    biasX: Float,
    biasY: Float,
    zoom: Float,
    onTransform: (dx: Float, dy: Float, dz: Float) -> Unit,
    onZoomStep: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleIcons.version) {
        value = withContext(Dispatchers.IO) { ModuleIcons.loadOriginal(context, moduleId) }
    }

    // 取景框不能用 fillMaxWidth+aspectRatio 拼正方形:平板/横屏下它会变成一大块,
    // 把下面那排「缩小/放大/复位」顶出可见区域(和 Miuix 侧同一个毛病)。
    // 这里按屏幕尺寸算一个受控边长(0.9 × 弹窗宽在平板上动辄 500dp)。
    val screen = LocalConfiguration.current
    val side = minOf(
        screen.screenWidthDp.dp * 0.7f,
        screen.screenHeightDp.dp * 0.4f,
        260.dp,
    ).coerceAtLeast(120.dp)

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
                        onTransform(-pan.x / w * 2f, -pan.y / h * 2f, gestureZoom)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onZoomStep(-0.25f) },
                modifier = Modifier.weight(1f),
                enabled = zoom > 1f,
            ) {
                Text("缩小")
            }
            TextButton(
                onClick = { onZoomStep(0.25f) },
                modifier = Modifier.weight(1f),
                enabled = zoom < 4f,
            ) {
                Text("放大")
            }
            TextButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
            ) {
                Text("复位")
            }
        }
    }
}
