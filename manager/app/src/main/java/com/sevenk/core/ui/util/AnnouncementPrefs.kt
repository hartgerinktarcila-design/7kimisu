package com.sevenk.core.ui.util

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * 开屏公告的"看过没有 / 要不要再提示"状态（v2.26 新增）。
 *
 * 背景 —— 用户 2026-09-22 的原话：
 *   「帮我在设置里做一个不再显示公告的开关，每次点开都弹出来」
 * 拆成两件事，本文件只管状态，弹不弹由 [com.sevenk.core.ui.component.AnnouncementPopup] 决定：
 *
 *   ① **"新公告才弹"**：以前是**每次冷启动都弹**（v2.2 起的老行为）—— 太烦。
 *      现在给公告内容算一个**指纹**（[keyOf]）：弹过、用户点掉，就把指纹存下来（[markSeen]）；
 *      下次冷启动指纹没变 ⇒ 不弹。哪天公告文案改了（真出了新公告）⇒ 指纹自动变了 ⇒ 再弹一次。
 *   ② **设置里的「不再显示公告」开关**（[neverShow] / [setNeverShow]），默认**关**；
 *      打开后无论新旧公告都不弹。
 *
 * ⚠️ 设计约束（写死在这里，以后别改坏 —— 这是 v2.25「不许往启动链加东西」的教训）：
 *   · 只读写 `settings` 这一份 SharedPreferences（纯内存映射查找），
 *     **不起线程、不读盘、不碰文件**，调用点还在原来的组合函数里 ⇒ 启动链一个字节没变。
 *   · **键名固定、别改**：改了会让老用户"已看过"的记录失效，公告重新弹一次。
 *   · 默认值 `false` / 空串 = **"没设置过"** ⇒ 升级上来的老用户第一次启动会看到一次公告
 *     （这是刻意的：新版本第一次进来提示一下，之后就不再烦）。
 */
object AnnouncementPrefs {

    /** 与 `SettingsRepositoryImpl` / `WallpaperPrefs` 读的是同一份 prefs。 */
    private const val PREFS = "settings"

    /** 设置里那个「不再显示公告」开关。默认 false = 有新公告时照常提示一次。 */
    private const val KEY_NEVER_SHOW = "announcement_never_show"

    /** 上一次"弹过并点掉"的公告指纹。 */
    private const val KEY_SEEN = "announcement_seen_key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否选了「不再显示公告」。 */
    fun neverShow(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEVER_SHOW, false)

    /** 打开/关闭「不再显示公告」。 */
    fun setNeverShow(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_NEVER_SHOW, value) }
    }

    /** 已经弹过并点掉的公告指纹；从没弹过就是空串。 */
    fun seenKey(context: Context): String =
        prefs(context).getString(KEY_SEEN, "") ?: ""

    /** 记下"这条公告弹过了、用户点掉了"。 */
    fun markSeen(context: Context, key: String) {
        prefs(context).edit { putString(KEY_SEEN, key) }
    }

    /**
     * 公告内容指纹 = `标题 + 换行 + 正文` 的 SHA-256 前 16 位十六进制。
     *
     * 为什么用**内容指纹**而不是手写一个版本号常量：以后改公告文案
     * （`ui/screen/home/About7k.kt` 里的 [com.sevenk.core.ui.screen.home.ANNOUNCEMENT_TEXT]）时，
     * 指纹会自动跟着变 ⇒ 老用户**只会再看到一次**，不需要谁记得去手动 bump 一个常量；
     * 反过来文案没动就一定不再弹。SHA-256 是纯内存计算（约 1KB 输入），冷启动开销可忽略。
     */
    fun keyOf(title: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$title\n$content".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
