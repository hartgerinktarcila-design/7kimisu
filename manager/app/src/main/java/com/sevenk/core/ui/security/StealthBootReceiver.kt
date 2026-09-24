package com.sevenk.core.ui.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后：
 *  1. 确保桌面图标在（顺便治好旧版本藏起来的图标）；
 *  2. 如果用户开过「网页管理器」，把它重新起来（开关存在 prefs 里，重启后仍生效）。
 */
class StealthBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        Stealth.ensureLauncherVisible(context)

        // 网页管理器不在这里管：它跑在 ksud 里，由 ksud 的 post-fs-data 钩子自启，
        // 跟 App 进程、开机广播都没关系（App 被划掉/一键清理也不影响）。
    }
}
