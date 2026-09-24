package com.sevenk.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.sevenk.core.core.crash.CrashEvidenceClassifier
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.util.LegacyDataMigration
import com.sevenk.core.ui.util.getRootShell
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "KernelSU"

// ══════════════════════════════════════════════════════════════════════
// 崩溃上报（2026-09-19 新增）
//
// 背景：出现过一次「部分机型打开就闪退」，当时没有任何上报能力，只能靠用户口述排查，
//      花了很久。补上这套最小可用的上报：
//
//   1. 会话标记：onCreate 里读 filesDir/.session_running —— 存在 = 上次进程没正常收尾；
//      读完立刻把标记写上（本次会话开始），MainActivity 正常收尾时删掉它。
//   2. 后台抓取：标记存在时，在**后台线程**上用 root shell 抓 tombstone + logcat；
//      **只有文本里确实出现我们自己的痕迹**才写进 filesDir/last_crash.txt，
//      没命中就什么都不写（避免把别人的崩溃/普通重启误报成我们的）。
//   3. Java 崩溃：装 Thread.setDefaultUncaughtExceptionHandler，把异常栈追加进同一个
//      文件，然后照常交回原来的默认 handler（绝不吞掉）。
//
// 铁律：所有 IO / root 调用 / 解析全部 runCatching 包住；主线程只做一次
//      「读标记 + 写标记」的极小磁盘操作，绝不在这里等 root、绝不阻塞首帧。
// ══════════════════════════════════════════════════════════════════════

/** 会话标记文件名（放 filesDir）。存在 = 上次进程没有正常收尾。 */
private const val SESSION_MARKER_NAME = ".session_running"

/** 崩溃报告文件名（放 filesDir）。UI 展示完会删掉它。 */
private const val CRASH_FILE_NAME = "last_crash.txt"

/** 报告文件上限：超过就先删旧再写，免得反复崩溃把文件撑爆。 */
private const val CRASH_FILE_MAX_BYTES = 256L * 1024L

/**
 * 崩溃证据的**分类器**已抽到 `core/crash/CrashEvidenceClassifier.kt`（v2.14）。
 *
 * 抽出去的理由有两条：
 *   ① 那个文件**不 import 任何 Android 类** ⇒ 可以在宿主机（JVM）上直接单测
 *      —— 本版"ksud 子进程被 SIGSYS 杀掉不算 App 崩溃"这条规则就是这么验的；
 *   ② 判据（真崩溃特征 / 本进程身份 / 内核探测被拦）集中在一处，不会再出现
 *      "抓取用的正则"和"判定用的正则"各写一份、改了一处忘了另一处。
 *
 * 抓取阶段仍然需要"哪些行算崩溃相关"，用 [CrashEvidenceClassifier.isCrashHintLine]。
 */

/** 只接受 tombstone 目录里正常的文件名，防止把奇怪名字拼进 shell 命令。 */
private val SAFE_FILE_NAME_REGEX = Regex("[A-Za-z0-9._-]{1,64}")

/**
 * 跑 root 命令用的线程池。只在后台线程上调用。
 *
 * 用 cached pool 而不是单线程：万一某条命令真的卡住（例如 su 弹窗没人点），
 * 卡死的只是一个 daemon 线程，后面的命令不会被它堵住，也不会拖住进程退出。
 */
private val crashShellExecutor by lazy {
    Executors.newCachedThreadPool { r ->
        Thread(r, "ksu-crash-shell").apply { isDaemon = true }
    }
}

/** 跑一条 root 命令拿 stdout。任何异常/超时都返回空串，调用方永远拿到字符串、永远不抛。 */
private fun runCrashShellCommand(cmd: String, timeoutSeconds: Long = 20L): String = runCatching {
    val task = crashShellExecutor.submit(
        Callable {
            getRootShell().newJob()
                .add(cmd)
                .to(ArrayList<String>(), null)
                .exec()
                .out
                .joinToString("\n")
        }
    )
    task.get(timeoutSeconds, TimeUnit.SECONDS)
}.getOrDefault("")

private fun crashTimestamp(): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}.getOrDefault("")

/** 时间 / 机型 / Android 版本 / 版本名 —— 报告开头的固定信息。 */
private fun crashDeviceSummary(): String = runCatching {
    "时间: ${crashTimestamp()}\n" +
        "机型: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
        "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}.getOrDefault("")

/**
 * 后台抓取：最新 1~2 个 tombstone + logcat 最近 3000 行里崩溃相关的行，拼成一段文本。
 * 抓不到（没 root / 命令报错 / 目录为空）就返回空串，调用方按「没命中」处理。
 */
private fun collectCrashEvidence(): String {
    val sb = StringBuilder()
    runCatching {
        // 1) tombstone：只看最新 1~2 个
        //    ⚠️ 实机（联想 Y700）上 `ls -t /data/tombstones/` 是 `tombstone_06`、`tombstone_06.pb`
        //       交替出现的 —— `.pb` 是同一次崩溃的 protobuf 二进制副本，会把名额占掉。
        //       这里排除掉，保证真的能拿到 1~2 个**文本** tombstone。
        val listed = runCrashShellCommand("ls -t /data/tombstones/ 2>/dev/null | head -5")
        val names = listed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && SAFE_FILE_NAME_REGEX.matches(it) && !it.endsWith(".pb") }
            .take(2)
            .toList()
        for (name in names) {
            val content = runCrashShellCommand("cat /data/tombstones/$name 2>/dev/null")
            if (content.isBlank()) continue
            // 只留「和我们有关」的行，免得把别的 App 的墓碑整篇塞进来
            val related = content.lineSequence()
                .filter { CrashEvidenceClassifier.isCrashHintLine(it) || it.contains(BuildConfig.APPLICATION_ID) }
                .toList()
            if (related.isEmpty()) continue
            sb.append("----- /data/tombstones/$name -----\n")
            related.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }

        // 2) logcat：先整段拉回来，再在本地按关键词筛（避免 shell 引号/转义踩坑）
        val log = runCrashShellCommand("logcat -d -t 3000 2>/dev/null")
        val logHits = log.lineSequence().filter { CrashEvidenceClassifier.isCrashHintLine(it) }.toList()
        if (logHits.isNotEmpty()) {
            sb.append("----- logcat -d -t 3000（只留崩溃相关行）-----\n")
            logHits.forEach { sb.append(it).append('\n') }
        }
    }.onFailure { Log.w(TAG, "抓取崩溃日志失败(忽略): ", it) }
    return sb.toString()
}

/**
 * 把抓到的证据交给 [CrashEvidenceClassifier] 分类（纯 Kotlin，可在宿主机上单测）。
 *
 * 返回值的用法见 [collectAndStorePreviousCrash]：
 *   OUR_CRASH → 写报告 + 弹窗；KERNEL_PROBE_BLOCKED → 只记日志；NOT_OURS → 什么都不做。
 */
private fun classifyEvidence(text: String): CrashEvidenceClassifier.Verdict =
    CrashEvidenceClassifier.classify(text, BuildConfig.APPLICATION_ID)

/** 追加写报告；失败只记一行日志，绝不抛。 */
private fun appendCrashReport(context: Context, text: String) {
    runCatching {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.isFile && file.length() > CRASH_FILE_MAX_BYTES) {
            runCatching { file.delete() }
        }
        file.appendText(if (text.endsWith("\n")) text else "$text\n")
    }.onFailure { Log.w(TAG, "写崩溃报告失败(忽略,不影响启动): ", it) }
}

/**
 * 后台抓取本次「上次崩溃」的证据。全程 runCatching：
 * 没 root、命令报错、解析失败 —— 一律静默放弃，不写文件、不弹任何东西。
 *
 * v2.14：结论是**三分类**（见 [classifyPreviousCrash]）——
 *   · [CrashEvidenceClassifier.Verdict.OUR_CRASH]            → 写 `last_crash.txt`（标题「自动抓取」），下次启动弹窗
 *   · [CrashEvidenceClassifier.Verdict.KERNEL_PROBE_BLOCKED] → **只写一行日志**，不落盘、不弹窗
 *   · [CrashEvidenceClassifier.Verdict.NOT_OURS]             → 什么都不做
 */
private fun collectAndStorePreviousCrash(context: Context) {
    runCatching {
        val evidence = collectCrashEvidence()
        when (classifyEvidence(evidence)) {
            CrashEvidenceClassifier.Verdict.OUR_CRASH -> Unit
            CrashEvidenceClassifier.Verdict.KERNEL_PROBE_BLOCKED -> {
                // 🔴 v2.14：这不是 App 崩了 —— 是 ksud / 内核探测的**子进程**被系统
                // （seccomp/SIGSYS 之类）拦下或杀掉。App 进程活得好好的。
                // 以前这里会写 last_crash.txt 并弹「崩溃上报」，用户就以为"闪退"。
                // 现在只留一行日志（真机上 `logcat -s 7kkernel` 能看到），不打扰用户。
                Log.i(TAG, "崩溃上报:确认是内核探测子进程被系统拦下(非 App 崩溃),只记日志不上报")
                return@runCatching
            }
            CrashEvidenceClassifier.Verdict.NOT_OURS -> {
                Log.i(TAG, "崩溃上报:上次会话没正常收尾,但没抓到我们自己的崩溃痕迹,不上报")
                return@runCatching
            }
        }
        // 用户可能已经看过/复制过这份报告（UI 已经删了文件），那就别再写回去 ——
        // 否则会出现「关掉弹窗后它又冒出来、下次启动再弹一次」。
        if (CrashReporter.isDismissedByUser()) return@runCatching
        appendCrashReport(
            context,
            "===== 崩溃上报（自动抓取）=====\n${crashDeviceSummary()}\n\n$evidence",
        )
        Log.i(TAG, "崩溃上报:已写入 ${File(context.filesDir, CRASH_FILE_NAME).absolutePath}")
    }.onFailure { Log.w(TAG, "后台抓取崩溃日志失败(忽略): ", it) }
}

/** Java 未捕获异常的文本（时间 / 机型 / Android 版本 / 版本名 / 线程名 / 完整栈）。 */
private fun buildJavaCrashText(thread: Thread, throwable: Throwable): String = buildString {
    append("===== 崩溃上报（Java 未捕获异常）=====\n")
    append(crashDeviceSummary()).append('\n')
    append("线程: ").append(thread.name).append('\n')
    append("异常: ").append(throwable.javaClass.name)
    throwable.message?.let { append(": ").append(it) }
    append('\n')
    append(Log.getStackTraceString(throwable))
    append('\n')
}

@Volatile
private var crashHandlerInstalled = false

/** 装默认异常处理器：先记盘，再把异常**原样交回**原来的 handler（千万别吞掉）。 */
private fun installCrashHandler(context: Context) {
    if (crashHandlerInstalled) return
    crashHandlerInstalled = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // ⚠️ handler 自己不许抛：记录这一步整段 runCatching
        runCatching { appendCrashReport(context, buildJavaCrashText(thread, throwable)) }
            .onFailure { Log.w(TAG, "记录 Java 崩溃失败(忽略): ", it) }
        // 交回原来的默认 handler，让系统照常杀进程 / 弹系统崩溃框
        previous?.uncaughtException(thread, throwable)
    }
}

/**
 * 会话检测：
 *   标记存在  → 说明上次进程没正常收尾（可能崩了）→ 起后台线程抓取
 *   然后不管怎样都把标记写上（表示本次会话开始）
 *
 * 只在「用户已解锁」之后调用（见 onCreate 的 directBootAware 早返回）。
 */
private fun startCrashSession(context: Context) {
    val marker = File(context.filesDir, SESSION_MARKER_NAME)
    val previousSessionEndedBadly = runCatching { marker.isFile }.getOrDefault(false)

    // 🟡（v2.18）**写盘 + 抓取挪到后台线程**：这段是在 `Application.onCreate` 的主线程上跑的，
    // `marker.writeText` 是一次磁盘写（StrictMode 违规；慢盘/低端机拖首帧）。
    // 读取保留在主线程（一次 stat，极快）—— 这样"先读上次、再写本次"的顺序语义完全不变。
    runCatching {
        thread(name = "ksu-crash-session", isDaemon = true) {
            // 先写上本次会话的标记；MainActivity 正常收尾时会删掉它
            runCatching { marker.writeText(crashTimestamp()) }
                .onFailure { Log.w(TAG, "写会话标记失败(忽略): ", it) }

            if (!previousSessionEndedBadly) return@thread

            Log.i(TAG, "崩溃上报:上次会话没有正常收尾,后台抓取崩溃痕迹")
            runCatching { collectAndStorePreviousCrash(context) }
        }
    }.onFailure { Log.w(TAG, "起崩溃会话线程失败(忽略): ", it) }
}

/**
 * 崩溃报告的读写入口（UI 层用）。
 *
 * 放在 Application 这个文件里，是为了让「收集」和「展示」共用同一份路径/上限常量，
 * 既不用新增文件、也不用动 KsuCli。
 */
object CrashReporter {

    /** 报告文件（filesDir/last_crash.txt）。 */
    fun reportFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    /** 读报告全文；没有 / 为空 / 读失败 → null。绝不抛。 */
    fun read(context: Context): String? = runCatching {
        val file = reportFile(context)
        if (!file.isFile) return@runCatching null
        file.readText().ifBlank { null }
    }.getOrNull()

    /**
     * 用户已经看过 / 复制过这份报告：删掉文件，并打上「别再写回来」的标记。
     * 否则后台抓取还在跑的时候会把文件重新写出来 → 关掉又弹出 / 下次启动再弹一次。
     */
    fun clear(context: Context) {
        userDismissed = true
        runCatching { reportFile(context).delete() }
    }

    /** 后台抓取落盘前问一句：用户是不是已经处理过这份报告了。 */
    internal fun isDismissedByUser(): Boolean = userDismissed

    /** 正常收尾：删掉会话标记（下次启动就不会认为是崩溃退出）。绝不抛。 */
    fun clearSessionMarker(context: Context) {
        runCatching { File(context.filesDir, SESSION_MARKER_NAME).delete() }
    }

    @Volatile
    private var userDismissed = false
}

lateinit var ksuApp: KernelSUApplication

class KernelSUApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    /**
     * ⚠️ 2026-09-19 修:`lateinit var` → `by lazy`。
     *
     * 它原来只在 `onCreate` 的**末尾**被赋值,而 `onCreate` 在
     * directBootAware(锁屏早启动)那条路径上会**直接 return** ——
     * 那条路径下 `okhttpClient` 永远没有初始化;等用户解锁、同一个进程里
     * 任何地方用到它(`ksuApp.okhttpClient`:模块仓库、模板仓库、下载器、WebUI 都在用)
     * 就会抛 `UninitializedPropertyAccessException` → 闪退。
     *
     * 改成惰性初始化后:不管启动时走到 `onCreate` 的哪一步,第一次真正用到它时才创建,
     * 因此**任何路径都不会再有未初始化**。类型和读法完全不变(仍是 `ksuApp.okhttpClient`),
     * 调用方零改动。
     */
    val okhttpClient: OkHttpClient by lazy { buildOkHttpClient() }

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { block ->
                block.proceed(
                    block.request().newBuilder()
                        .header("User-Agent", "KernelSU/${BuildConfig.VERSION_CODE}")
                        .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                )
            }
        // ⚠️ 2026-09-19 修:`Cache(File)` 构造会做磁盘 I/O,失败(目录不可写等)不该把 App 带崩。
        //    拿不到磁盘缓存就退化成"无缓存",网络功能照旧。
        runCatching { builder.cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024)) }
            .onFailure { Log.w(TAG, "okhttp 磁盘缓存不可用,改用无缓存: ", it) }
        return builder.build()
    }

    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        // ── 崩溃上报（2026-09-19 新增）──────────────────────────────────
        // ⚠️ 位置很讲究：必须在这条 directBootAware 早返回**之后**。
        //    锁屏早启动时 filesDir 还在 CE 加密里（读不了也写不了标记），而且那个进程
        //    多半马上被系统重起 —— 那种情况直接跳过抓取，不添乱。
        //    正常路径下这里只做「读一次标记 + 写一次标记」的极小磁盘操作；
        //    真正的抓取（root shell / tombstone / logcat）全部丢到后台线程。
        runCatching { startCrashSession(this) }
            .onFailure { Log.w(TAG, "崩溃上报:会话检测失败(忽略,不影响启动): ", it) }
        runCatching { installCrashHandler(this) }
            .onFailure { Log.w(TAG, "崩溃上报:安装异常兜底失败(忽略,不影响启动): ", it) }

        // ── 数据目录改名迁移（2026-09-20「彻底切割」）─────────────────────
        // 旧数据目录 /data/adb/ksu 要搬到 /data/adb/sevenk，否则密令/授权名单/
        // 网页管理器配置会"凭空消失"。非阻塞：只起一个后台线程去跑 root shell；
        // 本类内部把异常全吞掉，最坏情况是这次没搬成、留给 ksud 开机时搬。
        // 🟢 v2.9（2026-09-21）：同一趟里还会**幂等地补兼容软链**
        //    `/data/adb/ksu -> /data/adb/sevenk` —— 修"装了 Zygisk Next 等模块没效果 /
        //    报 Module files corrupted"那类回归（它们把旧路径写死了），见 LegacyDataMigration。
        runCatching { LegacyDataMigration.ensureMigratedOnce() }
            .onFailure { Log.w(TAG, "数据目录迁移:启动失败(忽略,不影响启动): ", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ⚠️ 2026-09-19 修:这一整块以前是裸的。`Application.onCreate` 里抛异常
            //    = **一启动就死**,没有第二次机会;而 HiddenApiBypass 是反射黑名单豁免、
            //    读设置也走磁盘,不同 ROM 上真的会抛。包起来后最坏只是没有"预测性返回"。
            runCatching {
                val enable = SettingsRepositoryImpl().enablePredictiveBack
                HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                setEnableOnBackInvokedCallback(applicationInfo, enable)
            }.onFailure { Log.w(TAG, "启用预测性返回失败(忽略,不影响启动): ", it) }
        }

        // ══════════════════════════════════════════════════════════════════
        // 🔴 v2.15：**Application 启动路径不发任何 root shell / ksud 调用**。
        //
        // 原来这里有一句
        //   `ViewModelProvider(this)[SuperUserViewModel::class.java].loadAppList()`
        // —— 它是"无条件"的：不管这台机器有没有 root、内核认不认我们，一启动就去
        // 建 root shell + 绑 libsu 的 `RootService`（`KsuService`）。
        // 而 libsu 的 RootService 会往 App 的 cache 里写一个**可写 dex**
        // （`cache/main.jar`）再用这个 shell 跑 app_process；shell 没 root 时
        // app_process 就以 App 的 uid 加载它 → ART 拒绝 → **abort()（SIGABRT/SI_QUEUE）**
        //   —— 这就是"打开就闪退"那条链的头。
        //
        // 超级用户页自己会加载（`SuperUserScreen.kt` 的 LaunchedEffect →
        // `viewModel.loadAppList()`），所以删掉这里**不丢功能**，只是把
        // 「这件事什么时候做」从"启动时无条件做"改成"用户真进那一页才做"。
        // ══════════════════════════════════════════════════════════════════

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        // ⚠️ 2026-09-19 修:`Os.setenv` 会抛 ErrnoException(例如受限 ROM 不给改环境变量),
        //    它在 onCreate 里,一抛就是启动即闪退。包起来后最坏只是 rust 的 temp_dir() 用默认值。
        runCatching { Os.setenv("TMPDIR", cacheDir.absolutePath, true) }
            .onFailure { Log.w(TAG, "设置 TMPDIR 失败(忽略): ", it) }
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
