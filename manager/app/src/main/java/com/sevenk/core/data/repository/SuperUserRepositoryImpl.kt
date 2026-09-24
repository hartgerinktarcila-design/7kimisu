package com.sevenk.core.data.repository

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.sevenk.core.IKsuInterface
import com.sevenk.core.Natives
import com.sevenk.core.data.model.AppInfo
import com.sevenk.core.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import com.sevenk.core.data.model.WEBVIEW_ZYGOTE_UID
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.KsuService
import com.sevenk.core.ui.util.KsuCli
import kotlin.coroutines.resume

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            // ══════════════════════════════════════════════════════════════════
            // 🟢 v2.16：**换回上游 KernelSU 的做法**（逐字对齐上游提交 7759d878）。
            //
            // 上游提交信息原文：
            //   "libsu bindOrTask() doesn't check shell uid, so app_process will crash
            //    because of loading a writable dex."
            //
            // 也就是说：`RootService.bindOrTask()` 本身**不看 shell 的 uid**。libsu 会把
            // 自带的 `main.jar` 拷进 App 的 cache（= 一个**可写 dex**），再用当前这个
            // shell 跑 `app_process`；shell 没有 root 时 app_process 就以 **App 自己的
            // uid** 加载那个可写 dex → Android 10+ 的 ART 拒绝 →
            // `Check failed: system_class_loader != nullptr` → `abort()`
            // → **signal 6 (SIGABRT), code -1 (SI_QUEUE)**，进程名
            // `com.sevenk.core:root:<userId>`。
            //
            // 上游的修法就是下面这三行：**没 root 就别去 bind**。
            // `KsuCli.SHELL.isRoot` 是 libsu 自己的结论 —— `ShellImpl` 的构造函数会
            // 同步跑一次 `echo SHELL_TEST` + `id`，输出含 `uid=0` 才把 status 置成
            // ROOT_SHELL（见 libsu 6.0.0 `ShellImpl.shellCheck()` 字节码），
            // **`build()` 返回时它已经写好**，并且进程已死会直接抛
            // `IOException("Created process has terminated")`。所以这个判据是可靠的，
            // 本项目自己的 `rootAvailable()`（KsuCli.kt）用的也正是它。
            //
            // ⚠️ v2.15 我们曾经在这里自创过一套 `RootServiceGate`（枚举 + 两个 KsuCli
            //    新 API + 自己跑 `id -u` 的 1500ms 探针），v2.16 已整段删除：
            //    它做的正是 libsu 构造期已经做过的事，而且和 `rootAvailable()` 的判据
            //    自相矛盾。**保持和上游一致，别再长回来。**
            // ══════════════════════════════════════════════════════════════════
            if (!KsuCli.SHELL.isRoot) {
                return@withContext Result.failure(
                    IllegalStateException("Root access is required")
                )
            }

            val result = connectKsuService {
                Log.w(TAG, "KsuService disconnected")
            }

            var currentBinder = result.first
            var currentConnection = result.second

            try {
                suspend fun reconnect(): IKsuInterface {
                    withContext(Dispatchers.Main) {
                        RootService.unbind(currentConnection)
                    }
                    val retry = connectKsuService { Log.w(TAG, "KsuService disconnected") }
                    currentBinder = retry.first
                    currentConnection = retry.second
                    return IKsuInterface.Stub.asInterface(currentBinder)
                }

                val pm = ksuApp.packageManager
                val start = SystemClock.elapsedRealtime()

                var iface = IKsuInterface.Stub.asInterface(currentBinder)
                val idsArray = try {
                    iface.userIds
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.userIds
                }

                val slice = try {
                    iface.getPackages(0)
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.getPackages(0)
                }

                val packages = slice.list
                val newApps = packages.filter {
                    val ai = it.applicationInfo ?: return@filter false
                    ai.uid != WEBVIEW_ZYGOTE_UID &&
                            (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                }.mapNotNull {
                    // 🟠（v2.19）`applicationInfo` 是平台类型、会被系统裁剪，`!!` 一碰就 NPE
                    //（与 data/model/AppInfo.kt 的 `?: -1` 同一个坑）。上面的 filter 已经保证
                    // 非空，这里再兜一次：真为 null 就跳过这条，语义不变（不会少一条正常数据）。
                    val appInfo = it.applicationInfo ?: return@mapNotNull null
                    val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = it,
                        profile = profile,
                    )
                }.toMutableList()

                // WebView Zygote is a single system UID, not a per-user package. Reuse the system icon.
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
                Pair(newApps, idsArray.toList())
            } finally {
                withContext(Dispatchers.Main) {
                    RootService.unbind(currentConnection)
                }
            }
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }

    private suspend inline fun connectKsuService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceDisconnected(name: ComponentName?) {
                    onDisconnect()
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive) {
                        cont.resume(binder as IBinder to this)
                    }
                }
            }

            cont.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    RootService.unbind(connection)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        RootService.unbind(connection)
                    }
                }
            }

            val intent = Intent(ksuApp, KsuService::class.java)

            val task = RootService.bindOrTask(
                intent,
                Shell.EXECUTOR,
                connection,
            )
            val shell = KsuCli.SHELL
            task?.let { shell.execTask(it) }
        }
    }
}
