package com.sevenk.core

import android.app.Application
import android.os.UserManager
import android.system.Os
import android.util.Log
import com.sevenk.core.security.Stealth
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "7kimisu"

lateinit var ksuApp: SevenkApplication

class SevenkApplication : Application() {

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        // libsu 初始化（参照旧版 / 上游 KernelSUApplication）
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20)
            )
        }.onFailure { Log.w(TAG, "设置 libsu 默认 builder 失败(忽略): ", it) }

        if (!isUserUnlocked()) {
            // directBootAware 早启动：filesDir 还是 CE 加密的，直接返回
            return
        }

        // 启动路径不发任何 root shell / ksud 调用（ksud 的 install 在 MainActivity，
        // 与旧版一致：只在"内核认主或隐身开着且 UAPI 够"时才试装）。
        // TODO: 旧版的 LegacyDataMigration（/data/adb/ksu → /data/adb/sevenk 兜底迁移）
        //       首版不移植，迁移由 ksud 开机流程负责。

        // 确保桌面图标在（顺便把历史版本藏起来的图标找回来）
        Stealth.ensureLauncherVisible(this)

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // rust 侧 temp_dir() 的工作环境
        runCatching { Os.setenv("TMPDIR", cacheDir.absolutePath, true) }
            .onFailure { Log.w(TAG, "设置 TMPDIR 失败(忽略): ", it) }
    }
}

/** 简单的后台 IO 执行器（页面内弹 toast 等场景用） */
object AppExecutors {
    val io: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "7k-io").apply { isDaemon = true }
        }
}

/** 在 IO 线程跑一段代码；失败静默（只留日志）。 */
fun runOnIo(block: () -> Unit) {
    thread(name = "7k-task", isDaemon = true) {
        runCatching { block() }.onFailure { Log.w(TAG, "后台任务失败(忽略): ", it) }
    }
}
