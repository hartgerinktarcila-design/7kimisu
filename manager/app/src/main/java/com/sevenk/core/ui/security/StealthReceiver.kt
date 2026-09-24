package com.sevenk.core.ui.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sevenk.core.R
import com.sevenk.core.ui.MainActivity

/**
 * 拨号盘密令入口:`*#*#70707#*#*`(可在设置里自定义)
 *
 * 关闭隐身、恢复到正常界面,并直接打开管理器。
 * 显式启动 [MainActivity](而不是 getLaunchIntentForPackage),
 * 这样和桌面入口的状态完全无关,一定能拉起界面。
 *
 * 密令比对用的是 [StealthCodeStore.acceptedCodes] —— **取并集**:
 * `/data/adb/sevenk/stealth_code` 那份(能跨卸载)和 App 设置那份,任意一个匹配就通过。
 * 所以「改过自定义密令 → 卸载重装」之后依然有效;而且即使两份不一致
 * (例如设置页写磁盘失败),也不会再把人锁死(以前会)。
 * 读那份要起 root shell,不能在主线程做,所以这里用 goAsync() 丢到子线程。
 */
class StealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return
        // 因为清单里只声明了 scheme,所以会收到"所有"密令。
        val dialed = intent.data?.host ?: return

        // 防暴力猜：默认密令是公开的 70707，任何人都能在拨号盘上乱试。
        // 连错到上限就临时挡下（纯内存计数，重启/进程被杀即清 —— 限流窗口只活在本进程内存里，
        // 不落盘、不影响正常使用）。
        // 放在最前面：被挡时连 root shell 都不起，省得白白读 /data/adb。
        val now = System.currentTimeMillis()
        if (now < blockedUntil) {
            android.util.Log.w(TAG, "密令尝试被暂时挡下（连错过多），剩余 ${blockedUntil - now}ms")
            return
        }

        val pending = goAsync()
        Thread {
            try {
                // 读 /data/adb 的密令要起 root shell,不能在主线程;
                // 但 Toast/启动界面必须回主线程做 —— 所以这里把"收尾"交给主线程。
                val restore = prepareRestore(context, dialed)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        finishRestore(context, restore)
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "密令收尾异常", t)
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "密令处理异常", t)
                runCatching { pending.finish() }
            }
        }.start()
    }

    /** 后台线程:比对 + 关隐身,返回需要主线程展示的结果 */
    private fun prepareRestore(context: Context, dialed: String): RestorePlan {
        // 必须自己比对:不是我们设置的那个数字就静默忽略,绝不打扰。
        // 注:第一项(内核标志)只为日志。
        //
        // ⚠️ 2026-09-16 改:比对**取并集**(磁盘那份 + App 设置那份),不再"只用优先级最高的那个"。
        //    原因:两份一旦不一致(最典型是设置页写磁盘那一步失败),
        //    用户拨自己刚设的号会被静默忽略 → 彻底锁死在隐身里(有用户反馈过)。
        //    取并集后两份里任意一个都能开锁;两边都没配时才退回默认值。
        val kernelState = runCatching { Stealth.kernelState() }.getOrDefault(-1)
        val accepted = StealthCodeStore.acceptedCodes(context)
        android.util.Log.i(TAG, "收到密令 dialed=$dialed 可接受=$accepted 隐身状态=$kernelState")
        if (dialed !in accepted) {
            android.util.Log.w(TAG, "密令不匹配,已忽略(可接受的是 $accepted)")
            // 防暴力猜：连错 MAX_FAILS 次就临时锁 BLOCK_MILLIS（纯内存，重启即清）。
            // 注意：比对逻辑本身（取并集、trim、大小写）一个字都没动，这里只是加计数。
            fails++
            if (fails >= MAX_FAILS) {
                blockedUntil = System.currentTimeMillis() + BLOCK_MILLIS
                fails = 0
                android.util.Log.w(TAG, "密令连错 $MAX_FAILS 次，暂停 ${BLOCK_MILLIS / 1000} 秒")
            }
            return RestorePlan(showRestore = false, message = "")
        }
        // 匹配成功：清空失败计数，别让以前手滑几次留下的计数影响后面
        fails = 0

        // ⚠️ v2.1(审计 P0-2):三态。以前 `wasEnabled = Stealth.isEnabled()` 会把
        //    "读失败(-1)"和"明确关着(0)"压成同一个 false,于是文案胡说八道。
        val wasEnabled = kernelState == 1
        val restored = Stealth.setEnabled(false)
        Stealth.ensureLauncherVisible(context)
        // 只有"**原本确实在隐身** 且 **这次确实关成功**"才补通知（见 notifyStealthRestored 注释）。
        // ⚠️ 必须额外看 wasEnabled：Stealth.setEnabled(false) 对**本来就关着**的情况
        //    也会返回成功，光看 restored 会给"本来就没开隐身"的人白发一条
        //    「已关闭隐身」，凭空打扰。
        if (wasEnabled && restored) {
            notifyStealthRestored(context)
        }
        android.util.Log.i(TAG, "恢复完成:kernelState=$kernelState restored=$restored")
        return RestorePlan(
            showRestore = true,
            // 四种情况说四句真话(审计 P0-2 + 🟡4 v2.4):
            //   ① 明确读到关着     → 「隐身模式未开启」
            //   ② 关成功           → 「已关闭隐身模式」
            //   ③ 内核不是我们的   → 「内核不是 7kimisu(请重启或卸载另一个管理器)」
            //                        ⚠️ v2.4 修的就是这里:以前它和 ④ 混成一句「内核太旧」,
            //                        在"设备上跑的是官方 KernelSU"时**说的是假话**。
            //   ④ 内核是我们的、但太旧 → 「内核太旧,不支持隐身模式」
            //   ⑤ 内核是我们的、功能也在、但本 App 没被认主 → 让用户重启/卸载另一个管理器
            message = when {
                kernelState == 0 -> context.getString(R.string.stealth_restore_not_enabled)
                restored -> context.getString(R.string.stealth_restore_disabled)
                !Stealth.kernelHasStealthFeature() && !Stealth.kernelLooksOurs() ->
                    context.getString(R.string.stealth_restore_foreign_kernel)
                !Stealth.kernelHasStealthFeature() ->
                    context.getString(R.string.stealth_restore_kernel_too_old)
                else -> context.getString(R.string.stealth_restore_not_recognized)
            },
        )
    }

    /** 主线程:提示 + 拉起界面 */
    private fun finishRestore(context: Context, plan: RestorePlan) {
        if (!plan.showRestore) return
        Toast.makeText(context, plan.message, Toast.LENGTH_LONG).show()

        val launch = Intent(context, MainActivity::class.java).apply {
            // 用 CLEAR_TASK 而不是 CLEAR_TOP:后者若已有实例会复用(NEW_INTENT),
            // 而复用出来的界面拿的是【旧 ViewModel 的旧状态】(隐身时算的"未安装"),
            // 就会出现"密令恢复成功但界面还是未安装,过一会才好"。清任务重建才是干净的。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val started = runCatching { context.startActivity(launch) }
        android.util.Log.i(TAG, "启动界面=${started.isSuccess}")
        if (started.isFailure) {
            Toast.makeText(context, "已关闭隐身,但界面启动失败,请点桌面图标", Toast.LENGTH_LONG).show()
        }
    }

    /** 供主线程收尾用的结果 */
    private data class RestorePlan(val showRestore: Boolean, val message: String)

    /**
     * 关隐身成功后发一条**可点**的通知。
     *
     * 调用条件：**原本确实开着隐身** 且 这次 `setEnabled(false)` 返回成功
     * （见 [prepareRestore] 里的 `wasEnabled && restored`）——本来就没开隐身时不发，
     * 别凭空打扰用户。
     *
     * 定位：只是**辅助反馈**。Android 10+ 禁止后台启动 Activity，密令在后台把隐身关掉后
     * [finishRestore] 里的 startActivity 可能被系统拦下、界面不会自己弹出来；
     * 这时通知栏至少能让用户看到"已经关掉了"，点一下就能打开管理器（比"什么都没有"强）。
     *
     * 撤回：[MainActivity.onResume] 回到管理器前台时会 `cancel([NOTIFICATION_ID])`
     * —— 用户已经看见界面了，这条辅助提示就没有存在意义。
     *
     * 隐私（渠道刻意做得不显眼）：
     * - 渠道 importance 用 [NotificationManager.IMPORTANCE_LOW]（不是 DEFAULT）：
     *   不响铃、不弹横幅、不进"打扰"级，只在通知栏静静列一条；
     * - 渠道 `lockscreenVisibility` 与 builder 的 `setVisibility` 都是
     *   [Notification.VISIBILITY_SECRET]：**锁屏上完全不显示内容** ——
     *   "隐身/管理器"本身就是敏感信息，不该在锁屏被人扫到。
     *
     * 权限：Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，**没给就静默失败** ——
     * 这里不弹权限请求框、不引导去设置、不做任何提示，绝不打扰用户。
     * （清单里已声明该权限，用户此前在别处授权过通知就会正常显示。）
     *
     * 整段 runCatching：接收器里任何异常都不能崩。本函数只在后台线程调用。
     */
    private fun notifyStealthRestored(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager == null) {
                android.util.Log.w(TAG, "没有 NotificationManager，跳过通知")
                return@runCatching
            }

            // Android 8+ 通知必须先有渠道，否则 notify 会被直接丢弃。
            // 渠道刻意"低存在感 + 锁屏不显示"，理由见上面的 KDoc（隐私）。
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(channel)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.i(TAG, "用户没给通知权限，静默跳过关隐身通知")
                return@runCatching
            }

            // 和 finishRestore 用同一套 flag：清任务重建，别复用旧界面（会拿到旧 ViewModel 状态）
            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("已关闭隐身")
                .setContentText("点这里打开管理器")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                // 和渠道的 lockscreenVisibility 保持一致：锁屏上不露出任何内容（隐私）
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
            android.util.Log.i(TAG, "已发出「关闭隐身」通知")
        }.onFailure {
            android.util.Log.w(TAG, "发通知失败(忽略，不影响关隐身)", it)
        }
    }

    companion object {
        const val DEFAULT_SECRET_CODE = "70707"
        private const val TAG = "7kkernel-stealth"

        /** 「关闭隐身」辅助通知的渠道 id / 渠道名 */
        private const val CHANNEL_ID = "stealth_restore"
        private const val CHANNEL_NAME = "隐身状态"

        /**
         * 「关闭隐身」辅助通知的通知 id。
         *
         * **故意公开**（不是 private）：[MainActivity.onResume] 回到管理器前台时要
         * `cancel(NOTIFICATION_ID)` 把这条提示撤掉，两处必须用同一个值，
         * 所以放这儿当唯一来源，别在别处再写一个字面量。
         */
        const val NOTIFICATION_ID = 70001

        /** 密令连错几次就临时锁住 */
        private const val MAX_FAILS = 5

        /** 锁多久（毫秒） */
        private const val BLOCK_MILLIS = 30_000L

        /**
         * 失败计数 / 解禁时刻：**纯内存**（进程被杀或重启即清零），
         * 只为挡住"脚本连续乱试"这种低成本暴力破解，不落盘、不影响正常使用。
         */
        @Volatile private var fails = 0
        @Volatile private var blockedUntil = 0L
    }
}
