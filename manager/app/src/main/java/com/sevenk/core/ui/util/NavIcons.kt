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

/**
 * 底部导航(主页/超级用户/模块/设置)的「自定义图标」。
 *
 * 用户选一张图 → 解码 → 缩到 [MAX_PX] 以内 → 存成 PNG 到应用私有目录 → 导航栏直接用。
 *
 * 为什么不直接内嵌网上的二次元图标:GitHub 上那些图的版权在画师手里(大多数连 LICENSE
 * 都没有),打进 APK 分发会有版权风险;让用户自己选自己的图,既好看又不涉及再分发。
 *
 * 注意:自定义图标只作用于「悬浮底栏」那种由我们自己画图标的样式
 * (Miuix 自带的无悬浮 NavigationBar / NavigationRail 只接受 ImageVector,塞不进位图)。
 */
object NavIcons {

    /** 顺序必须和底部导航一致:主页 / 超级用户 / 模块 / 设置 */
    val keys = listOf("home", "superuser", "module", "setting")

    /** 落盘尺寸上限(方形边长)。导航图标只有 24dp,256px 足够 2x~3x 屏,还省内存 */
    private const val MAX_PX = 256

    private const val DIR = "nav_icons"

    /** 改图后 +1,导航栏靠它触发重新加载 */
    var version by mutableIntStateOf(0)
        private set

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, key: String): File = File(dir(context), "$key.png")

    fun has(context: Context, key: String): Boolean = file(context, key).isFile

    fun hasAny(context: Context): Boolean = keys.any { has(context, it) }

    /** 从相册/文件选一张图,压小后存下来 */
    fun save(context: Context, key: String, uri: Uri): Boolean {
        val ok = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching false

            val scale = MAX_PX.toFloat() / maxOf(src.width, src.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                src
            }
            // 🔴（v2.19）**先写 `.part` → 校验 → rename 顶替**（照 v2.18 修好的
            // WallpaperStore::save / StatusDecoration 写法）。旧写法直接
            // `FileOutputStream(最终文件)`：写到一半失败（磁盘满 / 进程被杀）就把用户
            // 原来那张图标截断成半张 —— 界面上就是"图标裂了/糊了"，且回不去。
            // 现在同一目录内 rename：要么还是旧的、要么已经是新的，不存在半截。
            val dst = file(context, key)
            val tmp = File(dir(context), "$key.png.part")
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
            if (out !== src) src.recycle()
            true
        }.getOrDefault(false)
        if (ok) version++
        return ok
    }

    /** 读回来。@param targetPx 期望的显示尺寸(px),用来决定采样率 */
    fun load(context: Context, key: String, targetPx: Int = 0): ImageBitmap? = runCatching {
        val f = file(context, key)
        if (!f.isFile) return@runCatching null
        var sample = 1
        if (targetPx > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            }
        }
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }.getOrNull()

    fun clear(context: Context, key: String) {
        runCatching { file(context, key).delete() }
        version++
    }

    fun clearAll(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
        version++
    }
}
