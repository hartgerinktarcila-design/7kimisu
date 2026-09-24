package com.sevenk.core.ui.util

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import java.io.File

/**
 * 首页状态卡右下角那个大图标的「自定义图片」。
 *
 * 用户在设置里选一张图 → 复制到应用私有目录 → 首页立刻用它替换默认的绿色对勾。
 * 不依赖任何存储权限,也不影响主题配色;随时可以恢复默认。
 */
object StatusDecoration {

    /** 首页绿卡的高度下限(dp)。自裁预览也按这个比例画,保证所见即所得。
     *  高度沿革:78(内容撑开) → 104 → 140 → 187 → 249 → 332dp */
    const val CARD_HEIGHT_DP = 332f

    /**
     * 首页绿卡左右各留的边距(dp) —— 卡片宽度 = **屏宽 − 2×这个值**(默认 24dp)。
     *
     * 沿革:v0.13.28 曾把绿卡改成“贴边(满屏宽)”(即此值为 0),
     * v0.13.64 又按用户要求改回原来的宽度,与其它卡片对齐。
     * 首页的 `sideInset` 与设置里的“自裁取景”比例都读这一个值,别写死数字。
     */
    const val CARD_SIDE_INSET_DP = 12f

    private const val FILE_NAME = "status_decoration.png"

    /** 改图后 +1,首页靠它触发重新加载 */
    var version by mutableIntStateOf(0)
        private set

    /**
     * 状态卡图文件(直接放在 `filesDir/` 根下,没有子目录)。
     *
     * v2.1(审计 P1-4):保存前走共用的 [PrivateDir] 做"存在 + 可写"探测,
     * 而不是直接 `writeBytes` 抛异常(探针只认 `filesDir` 本身)。
     */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasCustom(context: Context): Boolean = file(context).isFile

    /** 从相册/文件选一张图保存下来 */
    fun save(context: Context, uri: Uri): Boolean {
        val ok = runCatching {
            // 先确认私有目录真的写得进去(磁盘满 / 目录权限坏掉时这里就直接 false)
            if (!PrivateDir.ensureWritable(context.filesDir)) return false
            val dst = file(context)
            // 🟠（v2.18）**流式拷贝 + 硬上限**。旧写法是
            // `openInputStream(uri)?.use { it.readBytes() }` —— 把用户选中的整个文件
            // 一次性读进 `ByteArray`：`image/*` 里 png/tiff 可以做到几百 MB，
            // 主线程回调上直接 OOM（`runCatching` 捕 Throwable 也救不回已经爆掉的堆）。
            // 现在按 64KB 块直接写盘，超过 64MB 就拒绝（卡片图不可能有这么大）。
            val tmp = File(context.filesDir, "$FILE_NAME.part")
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_SAVE_BYTES) {
                            tmp.delete()
                            return false
                        }
                        outs.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return false
            }
            // 同一目录内 rename 顶替：要么旧的、要么新的，不会有半截
            if (dst.exists() && !dst.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return false
            }
            true
        }.getOrDefault(false)
        if (ok) version++
        return ok
    }

    /** 状态卡图允许的最大字节数（远超实际需要；只是防 OOM 的上界） */
    private const val MAX_SAVE_BYTES = 64L * 1024 * 1024

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        version++
    }

    /** 内置默认图(打包在 APK 里,不是用户选的) */
    private fun loadBuiltin(context: Context, targetWidthPx: Int): ImageBitmap? = runCatching {
        val id = com.sevenk.core.R.drawable.art_default
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, id, bounds)
        var sample = 1
        if (targetWidthPx > 0 && bounds.outWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        }
        BitmapFactory.decodeResource(
            context.resources,
            id,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.asImageBitmap()
    }.getOrNull()

    fun load(context: Context, targetWidthPx: Int = 0): ImageBitmap? = runCatching {
        val f = file(context)
        if (!f.isFile) {
            // 没有自定义图 → 用内置的默认图(res/drawable-nodpi/art_default.jpg)。
            // 这样新装用户打开就是打扮好的样子;用户自选过就用自己的那张。
            return@runCatching loadBuiltin(context, targetWidthPx)
        }
        // 平板壁纸动辄 2560×1600，全尺寸解码要 16MB+ 内存，而卡片只有"整屏宽×187dp"。
        // 先只读尺寸、算出 2 的幂采样率，再真正解码 —— 采样后宽度仍不小于目标宽度，不会放大。
        var sample = 1
        if (targetWidthPx > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
            }
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 状态卡里那张自定义图的画法(卡片和「自裁」预览共用同一份,保证所见即所得)。
 *
 * @param biasX -1 左 / 0 居中 / 1 右      @param biasY -1 上 / 0 居中 / 1 下
 * @param zoom  放大倍数(1 = 铺满卡片;任何取景方式都可以缩放)
 */
@Composable
fun StatusDecorationImage(
    bitmap: ImageBitmap,
    biasX: Float,
    biasY: Float,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.then(
            if (zoom != 1f) Modifier.graphicsLayer(scaleX = zoom, scaleY = zoom) else Modifier
        ),
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(
            horizontalBias = biasX.coerceIn(-1f, 1f),
            verticalBias = biasY.coerceIn(-1f, 1f),
        ),
    )
}
