package com.sevenk.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后：确保桌面图标在（顺便治好旧版本藏起来的图标）。
 *
 * 网页管理器不在这里管：它跑在 ksud 里，由 ksud 的 post-fs-data 钩子自启，
 * 跟 App 进程、开机广播都没关系。
 */
class StealthBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        Stealth.ensureLauncherVisible(context)
    }
}
