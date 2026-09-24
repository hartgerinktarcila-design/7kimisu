package com.sevenk.core.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 模块卡片的**自定义背景图**（「卡面」），支持自裁。
 *
 * 和「模块图标」是两套独立存储，别混：
 *   · 图标 ModuleIcons —— 46dp 的小图，裁成**正方形**；
 *   · 卡面 ModuleCardArt —— 整张模块卡片的背景，**宽高比随卡片本身变化**
 *     （描述展开/收起都会变），所以取景框的比例是**打开那一刻卡片的真实比例**，
 *     裁完存下来；卡片后来变高变矮就按 Crop 兜一下。
 *
 * 文件（filesDir/module_cards/）：
 *   <模块id>.jpg        裁好、显示用的成品
 *   <模块id>.orig.jpg   用户选的原图（<=1024px，留着以后重新自裁）
 *   <模块id>.crop       上次取景参数 "biasX,biasY,zoom,aspect"
 */
object ModuleCardArt {

    /**
     * 显示时的压暗程度：0 = 原图，1 = 完全盖住。
     * 卡片上还有模块名/版本/作者和一堆按钮，不压一下会看不清；但别把图压死。
     */
    const val SCRIM = 0.45f

    /** 存图/成品的最大边长 */
    private const val MAX_SIDE = 1024

    private const val DIR = "module_cards"

    /** 上次的取景参数。aspect = 取景框宽/高（就是当时卡片的比例） */
    data class Crop(val biasX: Float, val biasY: Float, val zoom: Float, val aspect: Float)

    /** 改图后 +1，界面靠它触发重新加载 */
    var version by mutableIntStateOf(0)
        private set

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * 模块 id 里可能有空格/中文/斜杠，统一转成安全文件名。
     * 故意不和 ModuleIcons.safeId 共用：两边目录不同，各自自洽即可，少一层耦合。
     */
    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "module" }

    private fun artFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.jpg")
    private fun origFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.orig.jpg")
    private fun cropFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.crop")

    /** 有没有裁好的卡面（显示用） */
    fun has(context: Context, id: String): Boolean = artFile(context, id).isFile

    /** 有没有原图（能重新自裁） */
    fun hasOriginal(context: Context, id: String): Boolean = origFile(context, id).isFile

    fun hasAny(context: Context): Boolean = dir(context).listFiles()?.any { it.isFile } == true

    /** 上次的取景参数，没有就 null */
    fun loadCrop(context: Context, id: String): Crop? = runCatching {
        val f = cropFile(context, id)
        if (!f.isFile) return@runCatching null
        val p = f.readText().trim().split(",")
        if (p.size != 4) return@runCatching null
        Crop(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
    }.getOrNull()

    /**
     * 保存用户选的原图（压到 <=1024px），然后清掉旧的成品和取景参数。
     *
     * 用 JPEG 而不是 PNG：卡面是照片级别的图，PNG 一张能到 2~3MB，
     * 装十几个模块就爆了；JPEG q88 大约 200KB。卡面也不需要透明通道。
     *
     * @return 是否成功
     */
    fun saveOriginal(context: Context, id: String, uri: Uri): Boolean {
        val ok = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            var sample = 1
            val maxSide = max(bounds.outWidth, bounds.outHeight)
            while (maxSide / (sample * 2) >= MAX_SIDE) sample *= 2
            val src = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false

            val scale = MAX_SIDE.toFloat() / max(src.width, src.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else src
            // 🔴（v2.19）**先写 `.part` → 校验 → rename 顶替**（照 v2.18 修好的
            // WallpaperStore::save / StatusDecoration 写法）。旧写法直接覆盖 `<id>.orig.jpg`：
            // 压缩失败 / 进程被杀就把用户原图截断 —— 旧卡面还在显示、却再也重新自裁不了。
            // 新原图真正落盘成功之后，下面 `if (ok)` 才去删旧卡面和旧取景参数。
            val dst = origFile(context, id)
            val tmp = File(dir(context), "${safeId(id)}.orig.jpg.part")
            val compressed = FileOutputStream(tmp).use {
                out.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            if (!compressed || !tmp.isFile || tmp.length() <= 0L) {
                tmp.delete()
                return@runCatching false
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete()
                return@runCatching false
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return@runCatching false
            }
            if (out !== src) out.recycle()
            src.recycle()
            true
        }.getOrDefault(false)
        if (ok) {
            // 换了新图：旧的成品和取景参数都清掉，免得新图沿用上一张的框
            // ⚠️ v2.19：这两条删除**必须**在新 orig 已经 rename 落盘成功之后才跑
            //（见上面 .part+rename），失败时旧卡面原样保留。
            runCatching { artFile(context, id).delete() }
            runCatching { cropFile(context, id).delete() }
            version++
        }
        return ok
    }

    /**
     * 按取景参数把原图裁成卡面并保存（显示用的就是这一张）。
     *
     * @param aspect 取景框宽/高（打开自裁时卡片的真实比例）
     * @param biasX/biasY -1(左/上) ~ 1(右/下)；zoom 1~4
     */
    fun bake(
        context: Context,
        id: String,
        aspect: Float,
        biasX: Float,
        biasY: Float,
        zoom: Float,
    ): Boolean {
        val ok = runCatching {
            val f = origFile(context, id)
            if (!f.isFile) return@runCatching false
            val src = BitmapFactory.decodeFile(f.absolutePath) ?: return@runCatching false
            val r = cropRect(src.width, src.height, aspect, biasX, biasY, zoom)
            val cw = r.width().toInt().coerceAtLeast(1)
            val ch = r.height().toInt().coerceAtLeast(1)
            val x = r.left.toInt().coerceIn(0, (src.width - cw).coerceAtLeast(0))
            val y = r.top.toInt().coerceIn(0, (src.height - ch).coerceAtLeast(0))
            val cropped = Bitmap.createBitmap(src, x, y, cw, ch)
            val outW = minOf(MAX_SIDE, cw)
            val outH = (outW / aspect.coerceIn(0.2f, 5f)).toInt().coerceAtLeast(1)
            val out = Bitmap.createScaledBitmap(cropped, outW, outH, true)
            // 🔴（v2.19）成品卡面同样走 `.part` → rename 顶替（照 WallpaperStore::save）：
            // 旧写法直接覆盖 `<id>.jpg`，写一半失败就留下半张图（卡面裂开/只剩上半截）。
            val dst = artFile(context, id)
            val tmp = File(dir(context), "${safeId(id)}.jpg.part")
            val compressed = FileOutputStream(tmp).use {
                out.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            if (!compressed || !tmp.isFile || tmp.length() <= 0L) {
                tmp.delete()
                return@runCatching false
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete()
                return@runCatching false
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return@runCatching false
            }
            if (out !== cropped) out.recycle()
            if (cropped !== src) cropped.recycle()
            src.recycle()
            // 🔴（v2.19）取景参数也走原子写：旧写法 `cropFile.writeText(...)` 是裸写，
            // 写一半失败就留下半截参数（"biasX,biasY" 而不是四项），下次自裁解析失败、
            // 出现成品与参数"一半新一半旧"。参数写失败**只记日志、不算失败**：
            // 成品卡面已经 rename 落盘、显示没问题，参数只是下次自裁的初值。
            runCatching {
                val cropDst = cropFile(context, id)
                val cropTmp = File(dir(context), "${safeId(id)}.crop.part")
                cropTmp.writeText("$biasX,$biasY,$zoom,$aspect")
                if (cropDst.exists() && !cropDst.delete()) error("旧取景参数删不掉")
                if (!cropTmp.renameTo(cropDst)) {
                    cropTmp.delete()
                    error("取景参数 rename 失败")
                }
            }.onFailure {
                android.util.Log.w("ModuleCardArt", "取景参数写入失败(卡面已烘焙成功，不影响显示)", it)
            }
            true
        }.getOrDefault(false)
        if (ok) version++
        return ok
    }

    /**
     * **取景数学：预览和烘培共用这一份**（v0.13.57 就因为两套算法吃过一次亏）。
     *
     * 语义 = 从原图里取一块**指定宽高比**的矩形：
     *   先取"能放进原图的最大同比例矩形"，再按 zoom 缩小（zoom 越大取景越小 → 放大效果），
     *   最后按 biasX/biasY 在剩下的空间里平移（左/上 → 右/下）。
     *
     * @param aspect 取景框宽/高
     * @return 原图坐标系里的取景矩形（px）
     */
    fun cropRect(w: Int, h: Int, aspect: Float, biasX: Float, biasY: Float, zoom: Float): RectF {
        val a = aspect.coerceIn(0.2f, 5f)
        val z = zoom.coerceIn(1f, 4f)
        var rw: Float
        var rh: Float
        if (w.toFloat() / h.toFloat() >= a) {
            rh = h.toFloat()
            rw = rh * a
        } else {
            rw = w.toFloat()
            rh = rw / a
        }
        rw /= z
        rh /= z
        val maxX = (w - rw).coerceAtLeast(0f)
        val maxY = (h - rh).coerceAtLeast(0f)
        val left = (maxX * (biasX.coerceIn(-1f, 1f) + 1f) / 2f).coerceIn(0f, maxX)
        val top = (maxY * (biasY.coerceIn(-1f, 1f) + 1f) / 2f).coerceIn(0f, maxY)
        return RectF(left, top, left + rw, top + rh)
    }

    /** 读裁好的卡面（没有就 null） */
    fun load(context: Context, id: String, targetPx: Int = 0): ImageBitmap? = runCatching {
        val f = artFile(context, id)
        if (!f.isFile) return@runCatching null
        decodeSampled(f, targetPx)?.asImageBitmap()
    }.getOrNull()

    /** 读原图（自裁预览用） */
    fun loadOriginal(context: Context, id: String): ImageBitmap? = runCatching {
        val f = origFile(context, id)
        if (!f.isFile) return@runCatching null
        BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }.getOrNull()

    fun clear(context: Context, id: String) {
        runCatching { artFile(context, id).delete() }
        runCatching { origFile(context, id).delete() }
        runCatching { cropFile(context, id).delete() }
        version++
    }

    fun clearAll(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
        version++
    }

    private fun decodeSampled(f: File, targetPx: Int): Bitmap? {
        var sample = 1
        if (targetPx > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            }
        }
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * 把卡面铺满整块区域（**ContentScale.Crop 语义**：居中裁切铺满，不拉伸变形），
 * 再压一层底色保证卡片上的文字还能看清。
 *
 * Miuix / Material 两个界面**共用这一份**取景数学，免得两边各写一遍对不上
 * （v0.13.57 就因为预览和烘培两套算法吃过一次亏）。
 *
 * 调用方负责先把画布按卡片形状裁好：
 * · Miuix：`Modifier.squircleClip(CardDefaults.CornerRadius)`
 * · Material：`Modifier.clip(MaterialTheme.shapes.large)`
 *
 * 用法：放在 Card **外层**的 `drawWithContent` 里，先画图再 `drawContent()`，
 * 并且要把卡片底色改成透明 —— 否则卡片自己的底色（在 drawContent() 里画的）
 * 会把图整个盖掉。
 *
 * @param area 卡片整块区域（含卡片内部 padding，所以能铺到圆角处）
 * @param scrim 压暗色（一般用主题的 surface 色配 [ModuleCardArt.SCRIM] 透明度）
 */
fun DrawScope.drawCardArt(bitmap: ImageBitmap, area: Size, scrim: Color) {
    val dstW = area.width.toInt().coerceAtLeast(1)
    val dstH = area.height.toInt().coerceAtLeast(1)
    val srcW = bitmap.width.toFloat()
    val srcH = bitmap.height.toFloat()
    if (srcW <= 0f || srcH <= 0f) return
    // cover:取原图里一块和卡片同宽高比的区域，居中
    val scale = max(dstW / srcW, dstH / srcH)
    val sw = (dstW / scale).coerceAtMost(srcW)
    val sh = (dstH / scale).coerceAtMost(srcH)
    val sx = ((srcW - sw) / 2f).coerceAtLeast(0f)
    val sy = ((srcH - sh) / 2f).coerceAtLeast(0f)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(sx.toInt(), sy.toInt()),
        srcSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(dstW, dstH),
        filterQuality = FilterQuality.Medium,
    )
    drawRect(color = scrim, size = area)
}
