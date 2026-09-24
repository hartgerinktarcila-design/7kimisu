package com.sevenk.core.ui.util

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用私有目录的统一入口（v2.1，审计 P1-4）。
 *
 * 为什么单独抽这一层：壁纸、模块图标、状态卡图、导航图标以前各写各的
 * `File(filesDir, x).apply { mkdirs() }`，**全都不检查成败**。
 * 私有目录通常没问题，但"磁盘满 / 目录被别的 uid 改成不可写 / 目录被删成文件"
 * 时会静默失败，用户看到的是"点了没反应""图不见了"（真机上出现过
 * `/data/adb/sevenk` 目录 `drwxrwxrwx` 而文件属主是 root 的混乱状态）。
 *
 * 这里统一做三件事：
 *   ① `mkdirs()` 建目录；
 *   ② **真写一个探针文件再删掉**——比 `canWrite()` 可信（`canWrite()` 只看权限位，
 *      磁盘满 / SELinux 拦下它都发现不了）；
 *   ③ 探测不过就**自愈**：目录不存在就建；存在但是空目录就删了重建
 *      （**只有空目录才敢删**，里面有用户的图绝不能动）。
 *
 * 探测成功会按路径缓存（`filesDir` 在一个进程里不会变），所以不会每次调用都碰盘。
 */
internal object PrivateDir {

    /** 已确认可写的目录路径缓存；只缓存**成功**，失败每次都重试（可能只是临时的） */
    private val knownWritable = ConcurrentHashMap<String, Boolean>()

    /** 探针文件名 */
    private const val PROBE = ".7k_write_probe"

    /**
     * 拿到一个**保证存在且可写**的私有子目录。
     *
     * @return 目录；实在不行返回 `null` —— 调用方**必须**处理（返回失败原因 / 提示用户），
     *         不许再像以前那样当成一定成功。
     */
    fun ensure(context: Context, name: String): File? {
        val dir = File(context.filesDir, name)
        if (ensureWritable(dir)) return dir
        return null
    }

    /** 直接对一个目录做"存在 + 可写"的保证 */
    fun ensureWritable(dir: File): Boolean {
        if (knownWritable.containsKey(dir.absolutePath)) return true
        if (probe(dir)) {
            knownWritable[dir.absolutePath] = true
            return true
        }
        // 自愈①：不存在就建（含父目录）
        runCatching { if (!dir.isDirectory) dir.mkdirs() }
        if (probe(dir)) {
            knownWritable[dir.absolutePath] = true
            return true
        }
        // 自愈②：空的、但不是目录（或建不出来）→ 删掉重建。
        // 绝不会删有内容的目录 —— 那里面是用户的壁纸/图标。
        runCatching {
            val empty = !dir.exists() || (dir.isDirectory && dir.listFiles().isNullOrEmpty())
            if (empty) {
                dir.delete()
                dir.mkdirs()
            }
        }
        val ok = probe(dir)
        if (ok) knownWritable[dir.absolutePath] = true
        return ok
    }

    /**
     * 真写一个字节再删掉 —— 这才是"可写"的硬证据。
     * 任何异常都算不可写（不抛，交调用方转成给用户看的文案）。
     */
    private fun probe(dir: File): Boolean = runCatching {
        if (!dir.isDirectory) return false
        val f = File(dir, PROBE)
        f.writeBytes(byteArrayOf(0x37))
        val ok = f.length() > 0L
        f.delete()
        ok
    }.getOrDefault(false)
}
