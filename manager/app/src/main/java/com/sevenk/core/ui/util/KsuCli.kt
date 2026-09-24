package com.sevenk.core.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import android.widget.Toast
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import com.sevenk.core.BuildConfig
import com.sevenk.core.Natives
import com.sevenk.core.core.tasks.BootKernelVersion
import com.sevenk.core.core.tasks.ExtractImage
import com.sevenk.core.core.tasks.ProbeResult
import com.sevenk.core.core.utils.DataSourceChannel
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.security.Stealth
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

/**
 * 「上一次没建出 root shell」之后,隔多久才允许再建一次(毫秒)。
 *
 * D2(v2.2):自愈要和费电之间取个平衡 ——
 *   · 太短:真·没 root 的设备上,每次 `getRootShell()` 都起 2~3 个进程,白白费电;
 *   · 太长:用户刚授完 root 权限,界面要等很久才会"活过来"。
 * 3 秒是"用户点一下重试就够用"和"不会起进程风暴"之间的折中。
 * 一直失败就按 [SHELL_REBUILD_MAX_BACKOFF_MS] 翻倍退避。
 */
private const val SHELL_REBUILD_MIN_BACKOFF_MS = 3000L

/** 重建退避的上限(毫秒)。到顶之后最坏也只是"每分钟试一次"。 */
private const val SHELL_REBUILD_MAX_BACKOFF_MS = 60_000L

// ══════════════════════════════════════════════════════════════════════════
// 🟢 v2.16 删除：我们自创的「自己跑 `id -u` 探测 root」那套东西
//    （`SHELL_PROBE_TIMEOUT_MS` + `shellProbeExecutor` 单线程池 + `probeShellUid`）。
//
// 为什么删：**libsu 在 `Shell.Builder.build()` 里已经做过一模一样的事**，
// 而且是同步的。libsu 6.0.0 `ShellImpl` 的构造函数会：
//   ① 把 `shellCheck()` 提交到线程池，然后 **`future.get(timeout, SECONDS)` 阻塞等它**；
//   ② `shellCheck()` 先写 `echo SHELL_TEST\n` 确认对面是 shell（否则抛
//      `IOException("Created process is not a shell")`）；
//   ③ 再写 `id\n`，输出含 `uid=0` 才把 `status` 置成 `ROOT_SHELL`(=1)；
//   ④ 进程已经死了会直接抛 `IOException("Created process has terminated")`。
//   → 所以 `build()` 返回时 `shell.isRoot`（= `getStatus() >= 1`）**已经写好且可信**；
//     我们那套 1500ms 探针既重复、又引入了一个"超时就算没 root"的额外误判面。
//
// 本项目自己的 `rootAvailable()`（本文件下面）用的也一直是 `shell.isRoot` ——
// 也就是说 v2.15 那套探针和项目里既有的判据**自相矛盾**。v2.16 统一成 libsu 的结论。
// 证据（本机反汇编 libsu 6.0.0 core.aar 的 classes.jar）：
//   `javap -c com.topjohnwu.superuser.internal.ShellImpl` → 构造函数里
//   `FutureTask.get(JLjava/util/concurrent/TimeUnit;)` 后紧接
//   `putfield status:I`；`shellCheck()` 里 `String id\n` → `String uid=0`。
// ══════════════════════════════════════════════════════════════════════════

internal fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

/**
 * ⚠️ 2026-09-19 修:**这里绝对不能在"建对象"的时候去建 shell**。
 *
 * 原来写的是 `val SHELL: Shell = createRootShell()` —— 也就是把"起 root shell"放进了
 * `object KsuCli` 的**类初始化**里。类初始化一旦抛异常(起 shell 失败、libsu 内部
 * `Runtime.exec` 失败抛 `NoShellException`……),JVM 会把 `KsuCli` 这个类永久标记成
 * `Erroneous`:**之后主线程上任何一处 `KsuCli.xxx` / `getRootShell()` 都会抛
 * `NoClassDefFoundError` → 打开即闪退**,而且栈里看不到真正的原因(第一次的异常)。
 *
 * 现在改成:
 *  1. 类初始化只放几个 `@Volatile` 变量,**不碰任何可能失败的东西** → 初始化不可能失败;
 *  2. 真正的 shell 在**第一次被用到时**才建,并且整段包 `runCatching`(见 [safeRootShell]);
 *  3. 建失败**不会**被永久缓存,下次访问会重建 —— 一次失败不永久毒化(🔴 D2,v2.2);
 *  4. 实在建不出来时返回 [DeadShell] 占位:跑命令只会失败,**但不抛异常**,
 *     结果就是"这个 App 没有 root 能力",而不是崩。
 *
 * 对外行为不变:`SHELL` / `GLOBAL_MNT_SHELL` 仍然是 `Shell`(非空),调用方零改动。
 */
object KsuCli {
    /**
     * 缓存下来的建 shell 结果。`null` = 还没建过。
     *
     * 🔴 D2(v2.2 修,粉丝实测故障):这里以前是
     * `val SHELL: Shell by lazy { safeRootShell(false) }` —— `lazy` 会把**第一次**的结果
     * 永久钉死,包括 [DeadShell] 和"没拿到 root 的兜底 sh"。
     * 于是"App 刚启动那一瞬间内核/ksud 还没就绪"这一次失败,会让**整个进程**此后
     * 一直报「获取 Root 失败」—— 用户只能靠重启 App 才好,而他根本不知道为什么。
     *
     * 现在的规则:
     *   · 缓存里那个**已验证是 root**且进程还活着 → 直接用(正常路径,零额外开销);
     *   · 其它情况([DeadShell] / 没验到 root / shell 已死)→ **允许下次重建**,自愈;
     *   · 重建**限速 + 退避**（[SHELL_REBUILD_MIN_BACKOFF_MS] 起，翻倍到
     *     [SHELL_REBUILD_MAX_BACKOFF_MS]），否则"真的没 root"的设备上
     *     每次访问都要起 2~3 个进程 → 白白费电(本项目刚为费电问题做过专项优化)。
     */
    @Volatile
    private var cachedShell: Shell? = null

    @Volatile
    private var cachedGlobalMntShell: Shell? = null

    /** 上一次"重建尝试"的时间(`elapsedRealtime`),用于限速 */
    @Volatile
    private var lastRebuildAt: Long = 0L

    /** 当前的退避间隔（毫秒）。拿到 root 时复位成最小值，一直拿不到就翻倍到上限。 */
    @Volatile
    private var rebuildBackoffMs: Long = SHELL_REBUILD_MIN_BACKOFF_MS

    val SHELL: Shell get() = obtain(globalMnt = false)

    val GLOBAL_MNT_SHELL: Shell get() = obtain(globalMnt = true)

    /**
     * **快路径不加锁**：`getRootShell()` 是极热的调用点，
     * 每次访问都抢一把可能被"起 shell"（最长几秒）held 住的锁是不可接受的。
     * 正常情况下这里只做两次 `@Volatile` 读 + 一次 `isAlive()`。
     *
     * 🟢 v2.16：缓存判据从我们自创的 `ShellCreation.rootVerified` 换回
     * **libsu 自己的 `Shell.isRoot`**（理由见文件顶部那段"删除自创探针"的注释）。
     * `DeadShell.isRoot` 也是 false（它的 `getStatus()` 返回 `Shell.UNKNOWN`），
     * 所以占位 shell 同样不会被缓存。
     */
    private fun obtain(globalMnt: Boolean): Shell {
        val cached = if (globalMnt) cachedGlobalMntShell else cachedShell
        if (cached != null && cached.isRoot && cached.isAlive()) return cached
        return obtainSlow(globalMnt)
    }

    /** 慢路径：需要（重）建 shell 时才进来，加锁串行化。 */
    @Synchronized
    private fun obtainSlow(globalMnt: Boolean): Shell {
        // 双检：等锁的期间可能已经有别的线程建好了
        val cached = if (globalMnt) cachedGlobalMntShell else cachedShell
        if (cached != null && cached.isRoot && cached.isAlive()) return cached

        // ② 缓存不可用。要么还没建过(第一次),要么上一次没拿到 root。
        //    限速:冷却期内先把手头这个(用不了 root 的)shell 交出去,
        //    不重复起进程;过了冷却期就重建 —— 这就是"不用重启 App 也能自己好"。
        //    ⚠️ 只对"上一次就没拿到 root"退避；"验过 root 但进程死了"是异常情况，
        //      那种要**立刻重建**，不能被退避拖住。
        val now = android.os.SystemClock.elapsedRealtime()
        if (cached != null && !cached.isRoot && now - lastRebuildAt < rebuildBackoffMs) {
            return cached
        }
        lastRebuildAt = now

        val fresh = safeRootShell(globalMnt)
        if (globalMnt) cachedGlobalMntShell = fresh else cachedShell = fresh

        // 退避：拿到 root 就复位；还是没 root 就翻倍（上限 60 秒）。
        // 为什么需要退避：真·没 root 的设备上，每次重建要起 2~3 个进程；
        // 如果固定 3 秒一次，用户一直翻界面 = 一直在起进程，白费电。
        // 翻倍之后最坏也只是"每分钟试一次"，而拿到 root 的那一刻会立刻复位成 3 秒。
        rebuildBackoffMs = if (fresh.isRoot) {
            SHELL_REBUILD_MIN_BACKOFF_MS
        } else {
            (rebuildBackoffMs * 2).coerceAtMost(SHELL_REBUILD_MAX_BACKOFF_MS)
        }
        return fresh
    }
}

/**
 * 建 shell 的**唯一安全入口**:任何 `Throwable` 都吞掉,最坏给一个 [DeadShell] 占位。
 * 调用方(可能是主线程)永远不会因为它拿到异常。
 */
private fun safeRootShell(globalMnt: Boolean): Shell =
    runCatching { createRootShellDetailed(globalMnt) }.getOrElse { e ->
        Log.e(TAG, "创建 root shell 失败,退回占位 shell(本次运行没有 root 能力): ", e)
        DeadShell
    }

/**
 * 「什么都做不了」的占位 shell。
 *
 * 存在的唯一理由:连 `sh` 都起不来的时候,`getRootShell()` 仍然必须返回一个**非空**的
 * `Shell`(几十个调用方直接 `shell.newJob()...`,不能改成可空)。它身上跑任何命令都只会
 * 拿到 code = -1 的空结果 —— 也就是"没有 root 能力",而不是抛异常闪退。
 */
private object DeadShell : Shell() {
    override fun isAlive(): Boolean = false
    override fun getStatus(): Int = Shell.UNKNOWN
    override fun execTask(task: Shell.Task) {
        throw IOException("dead shell: no shell available")
    }

    override fun submitTask(task: Shell.Task) = Unit
    override fun newJob(): Shell.Job = DeadJob
    override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean = true
    override fun close() = Unit
}

/** [DeadShell] 的 Job:所有命令"立刻失败",不碰进程、不抛异常。 */
private object DeadJob : Shell.Job() {
    override fun to(dest: MutableList<String>?): Shell.Job = this
    override fun to(out: MutableList<String>?, err: MutableList<String>?): Shell.Job = this
    override fun add(vararg commands: String): Shell.Job = this
    override fun add(input: InputStream): Shell.Job = this
    override fun exec(): Shell.Result = DeadResult
    override fun submit(executor: Executor?, callback: Shell.ResultCallback?) {
        callback?.onResult(DeadResult)
    }

    override fun enqueue(): Future<Shell.Result> =
        CompletableFuture.completedFuture(DeadResult as Shell.Result)
}

/** [DeadShell] 的 Result:空输出 + code = -1,`isSuccess` 因此恒为 false。 */
private object DeadResult : Shell.Result() {
    override fun getOut(): MutableList<String> = ArrayList()
    override fun getErr(): MutableList<String> = ArrayList()
    override fun getCode(): Int = -1
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

/**
 * 起一个**真正能用**的 shell（优先 root）。
 *
 * 候选顺序：先自家 ksud（只有自家内核认它），不行再退回系统 `su`，最后退 `sh`。
 *
 * ⚠️ 2026-09-16 修（现象成立，但对 libsu 的归因在 v2.16 被更正）：
 *    当时的问题是「在**别人的 root 环境**里 `libksud.so debug su` 会立刻退出，
 *    而 `rootAvailable()` 永远 false → 安装页里「直接安装」不出现 → 从别的管理器
 *    转过来这条路堵死」。**现象是真的**，所以候选回退必须存在。
 *
 * 🟢 v2.16 更正：v2.15 之前这里写着"libsu 完全不管进程立刻退出的情况，`build()` 照样成功，
 *    所以必须自己跑 `id -u` 验一次"。**这个归因是错的**（本机反汇编 libsu 6.0.0 实证）：
 *    `ShellImpl.shellCheck()` 开头就是 `process.exitValue()` —— 进程已经退出会抛
 *    `IOException("Created process has terminated")`；没退但不对 `echo SHELL_TEST` 应答
 *    会抛 `IOException("Created process is not a shell")`。两者都会被 `build()` 抛出、
 *    被下面的 `catch` 接住 → **自然走到下一个候选**。而"是不是 root"由 libsu 自己在
 *    构造期写 `id` 并检查 `uid=0` 得出（`Shell.isRoot`）。所以自创的 `id -u` 探针是多余的，
 *    已在 v2.16 删除；判据统一用 [Shell.isRoot]（本项目 `rootAvailable()` 一直用的也是它）。
 */
fun createRootShell(globalMnt: Boolean = false): Shell = createRootShellDetailed(globalMnt)

/**
 * [createRootShell] 的实现：依次试候选命令，返回**第一个可用于 root 的** shell。
 *
 * 判据用 libsu 自己的 [Shell.isRoot]（构造期已由 `shellCheck()` 同步算好；
 * 进程已死 / 不是 root 都会被准确识别）。全部候选都失败时退回一个非 root 的 `sh`，
 * 连 `sh` 都起不来才给 [DeadShell] 占位 —— **任何情况下都返回非空 Shell、不抛异常**。
 */
private fun createRootShellDetailed(globalMnt: Boolean): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    val daemon = getKsuDaemonPath()

    // 候选顺序:先自家 ksud(只有自家内核认它),不行再退回系统 su。
    val candidates: List<Array<String>> = if (globalMnt) {
        listOf(
            arrayOf(daemon, "debug", "su", "-g"),
            arrayOf("su", "-mm"),
            arrayOf("su"),
        )
    } else {
        listOf(
            arrayOf(daemon, "debug", "su"),
            arrayOf("su"),
        )
    }

    for (cmd in candidates) {
        val shell = try {
            builder.build(*cmd)
        } catch (e: Throwable) {
            // 进程起不来 / 立刻退出 / 不是 shell —— libsu 在构造期就抛了，换下一个候选
            Log.w(TAG, "起 shell 失败(${cmd.first()}): ", e)
            null
        } ?: continue

        if (shell.isRoot) return shell

        Log.w(TAG, "这个 shell 不是 root,换下一个候选: ${cmd.first()}")
        // close() → ShellImpl.release() → Process.destroy(),不会阻塞
        // (注意别用无参的 waitAndClose(),那个是无限等)
        runCatching { shell.close() }
    }

    // 兜底:至少给一个能跑命令的非 root shell(与旧行为一致)。
    // ⚠️ 2026-09-19 修:`builder.build("sh")` 和 catch 里的 `builder.build()` 以前是**裸**的 ——
    //    它们就是"类初始化毒化"的最后一环:这里一抛,`object KsuCli` 的初始化就失败,
    //    之后主线程任何 `KsuCli.xxx` 都是 NoClassDefFoundError。现在两级兜底都自己吞掉。
    // ⚠️ D2(v2.2):这条兜底路径**没有 root**（`isRoot` = false），所以不会被 [KsuCli]
    //    缓存，下一次访问会重建 —— "这一次没 root"不会变成"这个进程永远没 root"。
    Log.e(TAG, "没有可用的 root shell,退回 sh")
    return try {
        builder.build("sh")
    } catch (e: Throwable) {
        Log.e(TAG, "连 sh 都起不来,再试一次默认 build: ", e)
        runCatching { builder.build() }.getOrElse { e2 ->
            // 退无可退:给占位 shell,让调用方拿到"命令都失败"而不是异常
            Log.e(TAG, "默认 build 也失败,只能给占位 shell: ", e2)
            DeadShell
        }
    }
}

fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(globalMnt), "${getKsuDaemonPath()} $args")
    }
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    // ⚠️ v2.14：整段包 runCatching —— 这个探测以前裸调 `.exec()`，
    //    任何异常（shell 已死 / 进程起不来 / 上面那行命令被信号打断）都会沿协程往上抛。
    //    调用方是设置页与首页的多个 produceState，抛出去就是"打开设置/首页闪退"。
    //    现在最坏只是返回 "unknown"，绝不让主进程受影响。
    val result = runCatching {
        getRootShell().newJob()
            .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec()
    }.onFailure { Log.w(TAG, "feature check $feature 探测失败（按 unknown 处理）", it) }.getOrNull()

    // ⚠️ 部分 ROM 的 seccomp 会在 ksud 探测内核那一刻把**子进程**用 SIGSYS 杀掉
    //    （机制见 ksud 侧 userspace/ksud/src/ksucalls.rs 顶部注释；v2.14 起 ksud 自己
    //    已经改成在独立子进程里探测、父进程不会被杀）。这里再兜一层：退出码
    //    128 + 31(SIGSYS) = 159 且没有任何输出时，按"不支持"处理，而不是抛异常。
    val sigsysKilled = result?.code == 128 + 31

    // ⚠️ v2.1(审计 P0-2)保留的规则:以前直接取第一行,读不到时返回空串,界面就落到 `else`
    //    分支显示成"这个特性正常可用" —— 一句没根据的假话。
    //    只认 ksud 真正吐出来的三种结论,其余(空输出/报错/意料之外的行)一律
    //    "unknown",界面明确显示"读不到内核状态"。
    result?.out?.map { it.trim() }
        ?.firstOrNull { it == "supported" || it == "managed" || it == "unsupported" }
        ?: if (sigsysKilled) "unsupported" else "unknown"
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

/**
 * 「ksud 安装失败」在**本进程内只提示一次**的闸门。
 *
 * 为什么需要它：`ksud install` 失败以前只留一行 `Log.w`（release 包被 R8 优化掉之后
 * 用户端完全看不见），表现就是"root 功能莫名失效"却没有任何提示。
 * 但启动路径每次都会调 [install]，直接弹 Toast 会变成每次启动都弹 —— 那更烦人。
 * 所以用一个静态（进程级）标志位：第一次失败提示一次，之后同进程内静默。
 * 进程被杀/重启后重新计数（这是刻意的：新进程里"ksud 装不上"这件事值得再提醒一次）。
 */
@Volatile
private var installFailureNotified = false

/**
 * 只在第一次失败时提示一次。用主线程 Handler 发出，保证从任何线程调用都安全
 * （[install] 既可能被主线程的 onCreate 调，也可能被后台的刷写流程调）。
 * 整段包 runCatching：连提示本身都不允许把进程带崩。
 */
private fun notifyInstallFailureOnce() {
    if (installFailureNotified) return
    installFailureNotified = true
    runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    ksuApp,
                    "内核组件(ksud)安装失败,部分 root 功能可能不可用;可重新打开 App 或到「安装」页重试",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot --data-path ${ksuApp.applicationInfo.deviceProtectedDataDir}", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
    // 成功不提示（老行为：静默成功）；失败提示一次，不再"静默失败"
    if (!result) {
        notifyInstallFailureOnce()
    }
}

fun listModules(): String {
    val shell = getRootShell()

    val out = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    // 🟠（v2.19）自己兜底（与同文件 getFeatureStatus 一致的写法）：
    // 这是 navigation badge 的**启动期**调用，裸调 JNI 一旦抛异常就会沿协程冒到界面
    // ⇒ "打开 App 就崩"。读不到按 0（角标不显示）处理，绝不外抛。
    return runCatching { Natives.getSuperuserCount() }.getOrDefault(0)
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    val file = File(ksuApp.cacheDir, "module.zip")
    try {
        // 🟡（v2.18）**流打不开/写出空文件就明确失败**。旧写法 `this?.copyTo(output)` 在
        // `openInputStream` 返回 null 时静默跳过，却照样拿 0 字节的 `module.zip` 去
        // `ksud module install`（用户看到的报错与真因无关）；而且 `file.delete()` 写在
        // 正常路径末尾，异常时根本不会执行，残包留在 cache 里。
        val input = resolver.openInputStream(uri)
            ?: return FlashResult(-1, "打不开这个模块包（权限或格式问题）", false)
        input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
        if (file.length() <= 0L) {
            return FlashResult(-1, "模块包是空的（复制失败）", false)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")
        return FlashResult(result)
    } finally {
        // 成功/失败/异常都清掉，绝不给下次留半截包
        runCatching { file.delete() }
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action $moduleId")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private fun writeLkmFile(lkm: LkmSelection): File? {
    if (lkm !is LkmSelection.LkmUri) return null
    val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
    // 🟠（v2.18）打不开就**返回 null**（调用方会明确报错），并且**先删掉上一次的残留**。
    // 旧写法 `openInputStream(...)?.use { ... }` 在 URI 打不开时什么都不做却仍 `return file`
    // —— 如果上次刷写留下的 `kernelsu-tmp-lkm.ko` 还在（异常/被杀导致 delete 没跑到），
    // ksud 就会拿**上一次那份 LKM**去 patch，用户以为用的是自己新选的 .ko。
    runCatching { file.delete() }
    val input = ksuApp.contentResolver.openInputStream(lkm.uri) ?: return null
    runCatching {
        input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
    }.onFailure {
        runCatching { file.delete() }
        return null
    }
    if (file.length() <= 0L) {
        runCatching { file.delete() }
        return null
    }
    return file
}

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        // 🟡（v2.18）打不开/写出空文件就明确失败：旧写法 `this?.copyTo(output)` 在
        // `openInputStream` 返回 null 时静默跳过，却把 0 字节的 boot.img 交给 ksud 去 patch。
        val f = File(ksuApp.cacheDir, "boot.img")
        val input = resolver.openInputStream(uri)
            ?: return FlashResult(-1, "打不开这个 boot/init_boot 镜像（权限或格式问题）", false)
        input.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs) } }
        if (f.length() <= 0L) {
            f.delete()
            return FlashResult(-1, "boot 镜像是空的（复制失败）", false)
        }
        f
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    if (ota) {
        cmd += " -u"
    }

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.LkmUri) {
        // 🟠（v2.18）用户**明确选了 .ko** 却没写成 ⇒ 不能静默按"没选 LKM"继续
        // （否则 ksud 会用别的方式补，结果与用户预期不符）。
        bootFile?.delete()
        return FlashResult(-1, "读不进你选的 .ko（权限或格式问题），换一个再试", false)
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }

    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val bootFile = File(ksuApp.cacheDir, "download-boot.img")
    var probedKmi: String? = null
    try {
        onStdout("- Downloading and extracting boot image")
        val channel = DataSourceChannel(newDownloadClient(), url)
        val magic = readMagic(channel)
        val image = ExtractImage(bootFile, onStdout)
        // Extract the KMI here while the payload is open. ZipFile closes the
        // channel it is built on, so probe on a separate channel.
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        if (magic == "CrAU") {
            image.consumePayload(channel, partition)
        } else {
            image.consume(channel, partition)
        }
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, e.message ?: "Download failed", false)
    }

    // init_boot/vendor_boot carry no kernel, so their KMI comes from the
    // payload's boot probe and must be passed explicitly. A remote download
    // is unrelated to this device, so ksud must not use the local kernel.
    val autoKmi = if (lkm is LkmSelection.KmiNone) {
        // 🟠（v2.18）`parseKmiFromBoot` 会解析**不受信**的 boot 镜像头：
        // 畸形头（kernelSize 为负 / 超大）会让它抛 IllegalArgumentException 或
        // OutOfMemoryError —— 这里旧写法在 `catch (e: Exception)` **之外**，
        // 异常会冒到调用方（FlashEffect 的协程）直接闪退。包一层兜底。
        (probedKmi ?: runCatching { BootKernelVersion.parseKmiFromBoot(bootFile) }.getOrNull())
            ?.also {
                onStdout("- Auto detected KMI: $it")
            }
    } else {
        null
    }
    if (autoKmi == null && lkm is LkmSelection.KmiNone) {
        bootFile.delete()
        return FlashResult(-1, "Failed to determine KMI from the package", false)
    }

    var cmd = "${getKsuDaemonPath()} boot-patch -b ${bootFile.absolutePath}"
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.LkmUri) {
        // 🟠（v2.18）同 installBoot：用户明确选了 .ko 却没写成 ⇒ 明确报错，不静默继续
        bootFile.delete()
        return FlashResult(-1, "读不进你选的 .ko（权限或格式问题），换一个再试", false)
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }
    if (autoKmi != null) cmd += " --kmi $autoKmi"
    cmd += " --partition $partition"
    // ksud defaults to cwd, which is read-only in the su session; use Downloads.
    val downloadsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cmd += " -o $downloadsDir"

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }
    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = Shell.getShell().newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    lkmFile?.delete()
    bootFile.delete()
    return FlashResult(result, false)
}

suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "probe start: $url")
    val channel = DataSourceChannel(newDownloadClient(), url)
    Log.d(TAG, "probe connected, size=${channel.size()}")
    val magic = readMagic(channel)
    Log.d(TAG, "probe magic: $magic")
    // Only list the partitions here; the KMI is extracted later when the
    // payload is downloaded for patching.
    val result = if (magic == "CrAU") {
        ExtractImage.probePayload(channel, withKmi = false)
    } else {
        ExtractImage.probe(channel, withKmi = false)
    }
    Log.d(TAG, "probe partitions: ${result.partitions}")
    result
}

private fun newDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true, true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

/**
 * 🟢 v2.7：读**磁盘上**那份持久化隐身标志 `/data/adb/sevenk/stealth`（内核写，掉电/重装都不丢）。
 *
 * 用途只有一个：内核的 `STEALTH_GET` ioctl **读不出来**时（三态里的 UNKNOWN，见
 * [Stealth.state]），给「这台机器是不是真开着隐身」补一个独立信号 ——
 * 决定安装页要不要藏起「直接安装（推荐）」（判据就在 `InstallScreen.kt` 里那一小段 `when`）。
 *
 * ⚠️ 为什么会有"内核读不出来"：`STEALTH_GET` 是 `only_manager` 命令，本 App
 *    **没被内核认主**时（重装/换 appid/待重启）会被拒；App 还有一条带世代号的安全阀命令
 *    （JNI `stealth_get` 里的 `KSU_IOCTL_STEALTH_GET_G`）也会一起失败 ——
 *    真机上就是这种情况（logcat：`no driver fd` + `回前台读隐身状态失败(state=-1)`）。
 *    此时磁盘上的标志是**唯一**还读得到的真相：内核 POST_FS_DATA 时就是按它加载的
 *    （`kernel/manager/stealth.c::ksu_stealth_load`）。
 *
 * 契约：明确读到 `'1'` → true；明确读到 `'0'` → false；**其余一律 null**
 * （没 root 读不到 / 文件不在 / 输出不认识）—— 调用方对 null 按"没法证明没开"处理，
 * 但**这个函数本身不替调用方做安全决定**。
 */
internal suspend fun getPersistedStealthFlag(): Boolean? = withContext(Dispatchers.IO) {
    runCatching {
        val out = ShellUtils.fastCmd(getRootShell(), "cat ${Stealth.STEALTH_FLAG_PATH}")
        when (out.trim().firstOrNull()) {
            '1' -> true
            '0' -> false
            else -> null
        }
    }.getOrElse { e ->
        Log.w(TAG, "读持久化隐身标志失败（返回 null，交给上层按保守方向处理）: ${e.javaClass.simpleName}")
        null
    }
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val escapedTemplate = template.replace("'", "'\\''")
    val cmd = """${getKsuDaemonPath()} profile set-template "$id" '$escapedTemplate'"""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null).exec().isSuccess
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
