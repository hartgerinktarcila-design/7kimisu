package com.sevenk.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.screen.home.ANNOUNCEMENT_TEXT
import com.sevenk.core.ui.screen.home.ANNOUNCEMENT_TITLE
import com.sevenk.core.ui.util.AnnouncementPrefs

/**
 * 开屏公告弹窗。
 *
 * 规则（用户 2026-09-22 定的最终口径 —— 原话「在设置里做一个不再显示公告的开关，
 * 每次点开都弹出来」⇒ ① 现在每次都弹太烦，② 设置里要有个彻底关掉的开关）：
 *
 *   1. 只有**冷启动 / 进程重启**才判断：`freshStart` 由 `MainActivity` 传
 *      `savedInstanceState == null`（主题切换导致的 Activity 重建不会重复弹）。
 *   2. 设置里「不再显示公告」**打开** ⇒ **永不弹**（[AnnouncementPrefs.neverShow]）。
 *   3. 否则**只在"有新公告"时弹一次**：把"标题 + 正文"的指纹
 *      （[AnnouncementPrefs.keyOf]）和上次弹过并点掉的指纹比 ——
 *      一样就不弹；文案变了（真出了新公告）才弹。
 *   4. ⚠️ v0.13.157 起**隐身时照常判断**（原来这里有一句"隐身模式不弹"，已删除）：
 *      公告是"一个**没有 root 的用户**点进来"就会看到的东西，隐身必须照常；
 *      而且公告文案是用户自己设的，弹什么由他决定 —— 不该由代码替他藏。
 *
 * 想改成"从后台切回来也弹"或者"一天只弹一次"：改 [shouldShow] 那一行就行。
 *
 * ⚠️ 性能红线（v2.25 的教训，别踩）：这里**只读 `settings` 这一份 SharedPreferences**
 *    （内存映射查找，同一份 prefs 在本次组合里已被主题/扁平化开关读过 ⇒ 已在内存），
 *    **不起线程、不读盘、不碰文件** —— 启动链一个字节都没加，
 *    唯一多的是一次 SHA-256（约 1KB 输入，微秒级）。
 *
 * ⚠️ 标记只在用户**点掉弹窗**（确认或取消）时才写：弹窗还挂着就把 App 划掉的话，
 *    下次冷启动会再弹一次 —— 宁可多弹一次，也别让用户漏看新公告。
 */
@Composable
fun AnnouncementPopup(freshStart: Boolean) {
    if (!freshStart) return

    val context = LocalContext.current

    // 指纹只算一次（remember）：重组不重复计算
    val key = remember { AnnouncementPrefs.keyOf(ANNOUNCEMENT_TITLE, ANNOUNCEMENT_TEXT) }
    val shouldShow = remember {
        !AnnouncementPrefs.neverShow(context) && AnnouncementPrefs.seenKey(context) != key
    }
    if (!shouldShow) return

    // 点「我知道了」、或者直接点掉弹窗，都算"看过了" ⇒ 记下指纹，同样内容之后不再弹
    val dialog = rememberConfirmDialog(
        onConfirm = { AnnouncementPrefs.markSeen(context, key) },
        onDismiss = { AnnouncementPrefs.markSeen(context, key) },
    )

    LaunchedEffect(Unit) {
        dialog.showConfirm(
            title = ANNOUNCEMENT_TITLE,
            content = ANNOUNCEMENT_TEXT,
            markdown = true,
            confirm = "我知道了",
        )
    }
}
