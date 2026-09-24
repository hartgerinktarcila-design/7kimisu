package com.sevenk.core.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 模块自定义图标。
 *
 * 用户可以给每个模块换一张图(自己的图/网图),并且可以**自裁**(拖动+双指缩放选个正方形区域)。
 * 文件都在应用私有目录,不需要任何存储权限:
 *   filesDir/module_icons/<模块id>.png        已经裁好的正方形图标(显示用)
 *   filesDir/module_icons/<模块id>.orig.png   原图(<=512px,留着以后重新自裁)
 *   filesDir/module_icons/<模块id>.crop       上次自裁的参数 "biasX,biasY,zoom"(重新自裁时当初始值)
 */
object ModuleIcons {

    /** 裁好之后的图标边长(px)。模块图标显示 ~46dp,256 足够 2x~3x 屏 */
    const val ICON_PX = 256

    /** 原图保存的最大边长 */
    private const val ORIG_MAX = 512

    private const val DIR = "module_icons"

    /** 改图后 +1,界面靠它触发重新加载 */
    var version by mutableIntStateOf(0)
        private set

    /**
     * 图标目录。v2.1(审计 P1-4)改走共用的 [PrivateDir]:
     * 以前只有一句 `mkdirs()` 且不看结果,目录不可写时会在后面某一步抛一个难懂的异常;
     * 现在统一"建目录 + 写探针验证 + 空目录自愈"。
     * 拿不到可写目录时仍然返回路径:**写入会失败并返回 false**,调用方已有提示。
     */
    private fun dir(context: Context): File =
        PrivateDir.ensure(context, DIR) ?: File(context.filesDir, DIR)

    /** 模块 id 里可能有空格/中文/斜杠,统一转成安全文件名 */
    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "module" }

    fun iconFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.png")
    private fun origFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.orig.png")
    private fun cropFile(context: Context, id: String): File = File(dir(context), "${safeId(id)}.crop")

    fun has(context: Context, id: String): Boolean = iconFile(context, id).isFile

    /** 有没有存过原图(能重新自裁) */
    fun hasOriginal(context: Context, id: String): Boolean = origFile(context, id).isFile
    fun hasAny(context: Context): Boolean = dir(context).listFiles()?.any { it.name.endsWith(".png") && !it.name.endsWith(".orig.png") } == true

    /** 上次自裁的参数(biasX, biasY, zoom),没有就返回 null */
    fun loadCrop(context: Context, id: String): Triple<Float, Float, Float>? = runCatching {
        val f = cropFile(context, id)
        if (!f.isFile) return@runCatching null
        val p = f.readText().trim().split(",")
        if (p.size != 3) return@runCatching null
        Triple(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }.getOrNull()

    /**
     * 保存用户选的原图(压到 <=512px 存起来,方便以后反复自裁)。
     * @return 是否成功
     */
    fun saveOriginal(context: Context, id: String, uri: Uri): Boolean {
        val ok = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            var sample = 1
            val maxSide = max(bounds.outWidth, bounds.outHeight)
            while (maxSide / (sample * 2) >= ORIG_MAX) sample *= 2
            val src = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false

            val scale = ORIG_MAX.toFloat() / max(src.width, src.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else src
            // 🔴（v2.19）**先写 `.part` → 校验 → rename 顶替**（照 v2.18 修好的
            // WallpaperStore::save / StatusDecoration 写法）。旧写法直接覆盖 `<id>.orig.png`：
            // 压缩失败 / 进程被杀就把用户原图截断 —— 旧成品还在显示、却再也重新自裁不了。
            // 现在新原图真正落盘成功之后，下面 `if (ok)` 才去删旧成品和旧取景参数，
            // 不会出现"新 orig 没写成、旧成品已经没了"的半新半旧状态。
            val dst = origFile(context, id)
            val tmp = File(dir(context), "${safeId(id)}.orig.png.part")
            val compressed = FileOutputStream(tmp).use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
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
            // 🟡（v2.18）缩放产物 `out` 也要回收。旧写法是
            // `if (out !== src) src.recycle()` 后跟无条件 `src.recycle()` ——
            // 重复回收 `src` 本身无害，但 `out` 永远漏 ⇒ 每换一次图标泄漏一张 ≤512×512 位图。
            if (out !== src) src.recycle()
            out.recycle()
            true
        }.getOrDefault(false)
        if (ok) {
            // 换新图:旧的裁切结果**和取景参数**都要清掉。
            // 不清 .crop 的话,新图会沿用上一张图的缩放/位置 ——
            // 表现就是"换了张图,取景框里的位置/大小还是上一张的"。
            // ⚠️ v2.19：这两条删除**必须**在新 orig 已经 rename 落盘成功之后才跑
            //（见上面 .part+rename），失败时旧成品原样保留，用户至少还看得到图标。
            runCatching { iconFile(context, id).delete() }
            runCatching { cropFile(context, id).delete() }
            version++
        }
        return ok
    }

    /**
     * 按自裁参数把原图裁成正方形图标并保存(显示用的就是这一张)。
     * @param biasX/biasY -1(左/上) ~ 1(右/下) ; zoom 1~4
     */
    fun bake(context: Context, id: String, biasX: Float, biasY: Float, zoom: Float): Boolean {
        val ok = runCatching {
            val f = origFile(context, id)
            if (!f.isFile) return@runCatching false
            val src = BitmapFactory.decodeFile(f.absolutePath) ?: return@runCatching false
            val out = bakeSquare(src, biasX, biasY, zoom, ICON_PX)
            // 🔴（v2.19）成品图标同样走 `.part` → rename 顶替（照 WallpaperStore::save）：
            // 旧写法直接覆盖 `<id>.png`，写一半失败就留下半张图（界面上图标裂开，且不可恢复）。
            val dst = iconFile(context, id)
            val tmp = File(dir(context), "${safeId(id)}.png.part")
            val compressed = FileOutputStream(tmp).use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
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
            // 🔴（v2.19）取景参数也走原子写：旧写法 `cropFile.writeText(...)` 是裸写，
            // 写一半失败就留下半截参数，下次自裁会读到垃圾值（成品与参数"一半新一半旧"）。
            // 参数写失败**只记日志、不算烘焙失败**：成品图标已经 rename 落盘、显示没问题，
            // 参数只是"下次打开自裁的初值"，丢了不影响图标。这样也不会因为参数写失败
            // 把一次已经成功的烘焙判成失败（那才会真的留下半新半旧的状态）。
            runCatching {
                val cropDst = cropFile(context, id)
                val cropTmp = File(dir(context), "${safeId(id)}.crop.part")
                cropTmp.writeText("$biasX,$biasY,$zoom")
                if (cropDst.exists() && !cropDst.delete()) error("旧取景参数删不掉")
                if (!cropTmp.renameTo(cropDst)) {
                    cropTmp.delete()
                    error("取景参数 rename 失败")
                }
            }.onFailure {
                android.util.Log.w("ModuleIcons", "取景参数写入失败(图标已烘焙成功，不影响显示)", it)
            }
            true
        }.getOrDefault(false)
        if (ok) version++
        return ok
    }

    /**
     * 取景数学:**预览和烘培共用这一份**,所以绝对不会出现"预览好看、装上不对"。
     *
     * 语义 = 从原图里取一个正方形区域:
     *   边长 = min(宽,高) / zoom    (zoom 越大取的越小 → 放大效果)
     *   位置 = 由 biasX/biasY 在 [-1,1] 之间平移(左/上 → 右/下)
     *
     * @return 原图坐标系里的那个正方形(px)
     */
    fun cropRect(w: Int, h: Int, biasX: Float, biasY: Float, zoom: Float): android.graphics.RectF {
        val z = zoom.coerceIn(1f, 4f)
        val side = (minOf(w.toFloat(), h.toFloat()) / z).coerceAtLeast(1f)
        val maxX = (w - side).coerceAtLeast(0f)
        val maxY = (h - side).coerceAtLeast(0f)
        val left = (maxX * (biasX.coerceIn(-1f, 1f) + 1f) / 2f).coerceIn(0f, maxX)
        val top = (maxY * (biasY.coerceIn(-1f, 1f) + 1f) / 2f).coerceIn(0f, maxY)
        return android.graphics.RectF(left, top, left + side, top + side)
    }

    /** 把取景区域裁成 out×out 的正方形(烘培) */
    private fun bakeSquare(src: Bitmap, biasX: Float, biasY: Float, zoom: Float, out: Int): Bitmap {
        val r = cropRect(src.width, src.height, biasX, biasY, zoom)
        val w = r.width().toInt().coerceAtLeast(1)
        val h = r.height().toInt().coerceAtLeast(1)
        val x = r.left.toInt().coerceIn(0, (src.width - w).coerceAtLeast(0))
        val y = r.top.toInt().coerceIn(0, (src.height - h).coerceAtLeast(0))
        val cropped = Bitmap.createBitmap(src, x, y, w, h)
        val scaled = Bitmap.createScaledBitmap(cropped, out, out, true)
        // 🟡（v2.18）中间那张 `cropped` 要回收：`createScaledBitmap` 在尺寸已经相等时会
        // **原样返回**同一个对象，所以只在不是同一个对象时回收。旧写法从不回收，
        // 每次烘焙都漏一张位图。
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /** 读裁好的图标(没有就 null) */
    fun load(context: Context, id: String, targetPx: Int = 0): ImageBitmap? = runCatching {
        val f = iconFile(context, id)
        if (!f.isFile) return@runCatching null
        decodeSampled(f, targetPx)?.asImageBitmap()
    }.getOrNull()

    /** 读原图(自裁预览用) */
    fun loadOriginal(context: Context, id: String): ImageBitmap? = runCatching {
        val f = origFile(context, id)
        if (!f.isFile) return@runCatching null
        BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }.getOrNull()

    fun clear(context: Context, id: String) {
        runCatching { iconFile(context, id).delete() }
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
