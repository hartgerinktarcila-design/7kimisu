package com.sevenk.core.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
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
import com.sevenk.core.BuildConfig
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ksuApp
import com.sevenk.core.security.Stealth
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val TAG = "KsuCli"

/**
 * 「上一次没建出 root shell」之后,隔多久才允许再建一次(毫秒)。
 * 太短:真·没 root 的设备上每次访问都起 2~3 个进程,白费电;
 * 太长:用户刚授完 root 权限,界面要等很久才"活过来"。
 */
private const val SHELL_REBUILD_MIN_BACKOFF_MS = 3000L

/** 重建退避的上限(毫秒)。到顶之后最坏也只是"每分钟试一次"。 */
private const val SHELL_REBUILD_MAX_BACKOFF_MS = 60_000L

internal fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

/**
 * ⚠️ **绝对不能在"建对象"的时候去建 shell**（类初始化一旦抛异常，JVM 会把类永久
 * 标记成 Erroneous）。现在：
 *  1. 类初始化只放几个 `@Volatile` 变量，不碰任何可能失败的东西；
 *  2. 真正的 shell 在第一次被用到时才建，整段包 runCatching；
 *  3. 建失败不会被永久缓存，下次访问会重建（限速 + 退避）；
 *  4. 实在建不出来时返回 [DeadShell] 占位：跑命令只会失败，但不抛异常。
 */
object KsuCli {
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

    /** 快路径不加锁：正常情况下只做两次 `@Volatile` 读 + 一次 `isAlive()`。 */
    private fun obtain(globalMnt: Boolean): Shell {
        val cached = if (globalMnt) cachedGlobalMntShell else cachedShell
        if (cached != null && cached.isRoot && cached.isAlive()) return cached
        return obtainSlow(globalMnt)
    }

    /** 慢路径：需要（重）建 shell 时才进来，加锁串行化。 */
    @Synchronized
    private fun obtainSlow(globalMnt: Boolean): Shell {
        val cached = if (globalMnt) cachedGlobalMntShell else cachedShell
        if (cached != null && cached.isRoot && cached.isAlive()) return cached

        // 限速:冷却期内先把手头这个(用不了 root 的)shell 交出去,不重复起进程。
        // ⚠️ 只对"上一次就没拿到 root"退避；"验过 root 但进程死了"是异常情况，要立刻重建。
        val now = SystemClock.elapsedRealtime()
        if (cached != null && !cached.isRoot && now - lastRebuildAt < rebuildBackoffMs) {
            return cached
        }
        lastRebuildAt = now

        val fresh = safeRootShell(globalMnt)
        if (globalMnt) cachedGlobalMntShell = fresh else cachedShell = fresh

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
 */
private fun safeRootShell(globalMnt: Boolean): Shell =
    runCatching { createRootShellDetailed(globalMnt) }.getOrElse { e ->
        Log.e(TAG, "创建 root shell 失败,退回占位 shell(本次运行没有 root 能力): ", e)
        DeadShell
    }

/**
 * 「什么都做不了」的占位 shell。
 * 它身上跑任何命令都只会拿到 code = -1 的空结果 —— 也就是"没有 root 能力",而不是抛异常闪退。
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
 * "是不是 root"由 libsu 自己在构造期写 `id` 并检查 `uid=0` 得出（`Shell.isRoot`）；
 * 进程已死 / 不是 shell 都会在 `build()` 时抛出、被 catch 接住换下一个候选。
 */
fun createRootShell(globalMnt: Boolean = false): Shell = createRootShellDetailed(globalMnt)

private fun createRootShellDetailed(globalMnt: Boolean): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    val daemon = getKsuDaemonPath()

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
            Log.w(TAG, "起 shell 失败(${cmd.first()}): ", e)
            null
        } ?: continue

        if (shell.isRoot) return shell

        Log.w(TAG, "这个 shell 不是 root,换下一个候选: ${cmd.first()}")
        runCatching { shell.close() }
    }

    // 兜底:至少给一个能跑命令的非 root shell。
    Log.e(TAG, "没有可用的 root shell,退回 sh")
    return try {
        builder.build("sh")
    } catch (e: Throwable) {
        Log.e(TAG, "连 sh 都起不来,再试一次默认 build: ", e)
        runCatching { builder.build() }.getOrElse { e2 ->
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
    val result = runCatching {
        getRootShell().newJob()
            .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec()
    }.onFailure { Log.w(TAG, "feature check $feature 探测失败（按 unknown 处理）", it) }.getOrNull()

    // 部分 ROM 的 seccomp 会在 ksud 探测内核那一刻把子进程用 SIGSYS 杀掉：
    // 退出码 128 + 31(SIGSYS) = 159 且没有任何输出时，按"不支持"处理。
    val sigsysKilled = result?.code == 128 + 31

    // 只认 ksud 真正吐出来的三种结论,其余(空输出/报错/意料之外的行)一律 "unknown"。
    result?.out?.map { it.trim() }
        ?.firstOrNull { it == "supported" || it == "managed" || it == "unsupported" }
        ?: if (sigsysKilled) "unsupported" else "unknown"
}

suspend fun getFeaturePersistValue(feature: String): Long? = readFeatureValue("--config $feature")

// 内核里的当前生效值（区别于 --config 读出来的持久化值）
suspend fun getFeatureValue(feature: String): Long? = readFeatureValue(feature)

private suspend fun readFeatureValue(args: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get $args").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

/** 「ksud 安装失败」在本进程内只提示一次的闸门（进程级，重启后重新计数）。 */
@Volatile
private var installFailureNotified = false

private fun notifyInstallFailureOnce() {
    if (installFailureNotified) return
    installFailureNotified = true
    runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    ksuApp,
                    ksuApp.getString(R.string.ksud_install_failed),
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
    // 启动期调用，裸调 JNI 一旦抛异常就会崩；读不到按 0 处理。
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
    val file = File.createTempFile("module-", ".zip", ksuApp.cacheDir)
    try {
        try {
            val input = ksuApp.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
        } catch (e: IOException) {
            Log.w(TAG, "module $uri not installed: ${e.message}")
            val message = "! ${e.message ?: "Failed to read module"}"
            onStderr(message)
            return FlashResult(1, message, false)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i(TAG, "install module $uri result: $result")
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

    Log.i(TAG, "Module runAction result: $result")

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

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
}

/**
 * boot 修补：`boot-patch -b <file>` 或（bootUri == null）直接安装 `boot-patch -f`。
 * 首版最简流程：不接 LKM 自选 / KMI 手选 / 模块黑名单（TODO 后置）。
 */
fun installBoot(
    bootUri: Uri?,
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
        // 打不开/写出空文件就明确失败：0 字节的 boot.img 绝不能交给 ksud 去 patch。
        val f = File(ksuApp.cacheDir, "boot.img")
        val input = resolver.openInputStream(uri)
            ?: return FlashResult(-1, ksuApp.getString(R.string.flash_open_image_failed), false)
        input.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs) } }
        if (f.length() <= 0L) {
            f.delete()
            return FlashResult(-1, ksuApp.getString(R.string.flash_empty_image), false)
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

    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i(TAG, "install boot result: ${result.isSuccess}")

    bootFile?.delete()

    // 直接安装成功时给重启按钮；顺手装一次 ksud
    val showReboot = bootUri == null && result.isSuccess
    if (showReboot) {
        install()
    }
    return FlashResult(result, showReboot)
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
 * 读**磁盘上**那份持久化隐身标志 `/data/adb/sevenk/stealth`（内核写，掉电/重装都不丢）。
 *
 * 用途只有一个：内核的 `STEALTH_GET` ioctl **读不出来**时（三态里的 UNKNOWN，见
 * [Stealth.state]），给「这台机器是不是真开着隐身」补一个独立信号。
 *
 * 契约：明确读到 `'1'` → true；明确读到 `'0'` → false；**其余一律 null**。
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
        Log.w(TAG, "读持久化隐身标志失败（返回 null）: ${e.javaClass.simpleName}")
        null
    }
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
