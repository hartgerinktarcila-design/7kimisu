package com.sevenk.core.ui.util

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sevenk.core.ksuApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 数据目录改名迁移（2026-09-20「彻底切割」B 方案）。
 *
 * 名字改了：数据目录从 `/data/adb/ksu` 改成了 `/data/adb/sevenk`。
 * 老用户机器上那份**旧目录还要搬过来**，否则密令、授权名单、网页管理器配置会"凭空消失"。
 *
 * ══════════════════════════════════════════════════════════════════
 * 🔴A 2026-09-20 审计后的修正：**只有"新内核在跑"才能搬**（本类以前是无条件 `mv` ✗）
 * ══════════════════════════════════════════════════════════════════
 *
 * 数据目录是**编译期写死在内核里**的：老内核读 `/data/adb/ksu`，新内核读 `/data/adb/sevenk`。
 * 「先装新 APK、还没刷新内核」时如果把旧目录搬走：
 *   · 隐身标志读不到 → 内核默认关闭隐身 = **暴露** ✗
 *   · 授权名单读不到 → 重启后已授权应用要重新授权 ✗
 *   · 之后内核再写授权名单还会因为父目录没了而**写不进去**
 *
 * 所以本类**不再自己动任何数据**，只做两件事：
 *   1. 用**本 APK 自带的那份 ksud**（`nativeLibraryDir/libksud.so`，一定是新版、一定带门控）
 *      先跑 `migrate --probe-only` 做预检，看它打的 `KERNEL_DATADIR=` 结论；
 *   2. **只有**结论是 `new_datadir` 才继续跑 `migrate`。
 *
 * ⚠️ 为什么必须用 APK 自带那份、不能调 `/data/adb/ksud`：
 *    设备上那份可能是**上一版的旧 ksud**（旧版的 `auto_migrate()` 是**无条件搬**的，
 *    连命令行参数都不看 —— 它在 clap 解析**之前**就执行了）。调它就等于把数据搬走 ✗。
 *
 * ⚠️ 为什么 App 自己不做探测：探测要发内核 ioctl（命令 27/28），
 *    App 这边只有 JNI（`cpp/` 这次不许动）和 root shell 两条路，都拿不到内核句柄；
 *    真正可靠的"行为探测"（让内核写一遍 stealth，看它写进哪个目录）在 ksud 里 —— 见
 *    `userspace/ksud/src/migrate.rs` 的 `probe_datadir_with()`（宿主上有单测逐条覆盖）。
 *
 * 老实说明：`--probe-only` 那一次调用里，ksud 进程开头那次自动迁移（同样经过门控）
 * 也会跑；所以第二步 `migrate` 多数时候只是幂等复跑。真正的门控在 ksud 里，这里只保证
 * **App 自己一个字节都不搬**、而且只在结论明确时才继续。
 *
 * ══════════════════════════════════════════════════════════════════
 * 🟢 v2.9（2026-09-21）：**兼容软链** `/data/adb/ksu` → `/data/adb/sevenk`
 * ══════════════════════════════════════════════════════════════════
 *
 * 用户实测的回归：装了 **Zygisk Next** 没效果、它自己的界面报 "Module files corrupted" ✗。
 * 根因：改名之后**大量第三方模块把自己写死的 `/data/adb/ksu/...` 当成 KernelSU 的数据目录**
 * （`bin/ksud`、`bin/busybox`、`lib/…`），它们找不到自己的文件。修法 = 在新旧路径之间留一条
 * **软链**（权威实现在 ksud 的 `migrate.rs::ensure_compat_link_at`，App 侧只是启动时的兜底）。
 *
 * 🔑 为什么留软链**不影响隐身**：`/data/adb` 只有 root 能读（实测设备 HA247EY4：
 * adb shell 里 `ls /data/adb` = Permission denied），没有 root 的检测类 App
 * **连看都看不到**这条软链；而需要旧路径的模块脚本 / su 会话本来就有 root。
 *
 * ⚠️ App 侧**只做最保守的一件事**：旧路径上**什么都没有**、且新目录是真实目录时，才建软链。
 *   · 旧路径已经是正确的软链 → 什么都不做（幂等）；
 *   · 旧路径是**真实目录**（空的或有内容的）→ **一律不碰**：要不要把它改名腾出路径，
 *     必须先确认"新内核在跑"（要问内核），那是 ksud 里带门控的实现该干的活；
 *   · 旧路径是别的软链 / 别的类型 → 一律不碰，只记日志。
 * 任何失败只记日志，绝不崩、绝不拖慢启动。
 *
 * 铁律：跑在后台线程、任何异常都吞掉只记日志 —— 绝不拖慢启动、绝不影响任何功能。
 */
object LegacyDataMigration {

    private const val TAG = "7kkernel-migrate"

    /** 旧数据目录（改名前的名字）。**故意保留**：迁移必须知道旧目录叫什么。 */
    private const val LEGACY_DIR = "/data/adb/ksu"

    /** 新数据目录（改名后） */
    private const val NEW_DIR = "/data/adb/sevenk"

    /** 门控结论里"确认新内核在跑"的那一个值（与 ksud `DatadirProbe::as_str` 一致） */
    private const val VERDICT_NEW_DATADIR = "new_datadir"

    /** 每个进程只自动跑一次 */
    private val started = AtomicBoolean(false)

    /**
     * 🔴 D3(v2.2)：**本次真的搬过东西**（或删掉了旧目录）→ 界面要提示用户"请重启一次"。
     *
     * 为什么非要重启：迁移只改**磁盘上的文件**，而**内核只在开机时读一次授权名单**
     * （读进内存之后不再重读）。所以"名单已经搬到新目录了"这件事，内核要等下次开机才知道；
     * 在重启之前，内核眼里那份名单还是**启动那一刻的旧内容**。
     *
     * 更危险的是：**重启前如果授权了任何新 App**，内核会把内存里那份（旧的）名单
     * 写回磁盘 → **把刚迁过来的旧授权整个覆盖掉**，而且不可恢复。
     * 所以提示里必须写清"重启前先别授权任何新 App"。
     *
     * 存成"待提示"标志（持久化）：迁移是在后台线程发生的，界面可能还没起来；
     * 用户点掉之后就不再显示 —— 满足"**不超过一次**"。
     */
    private const val NOTICE_PREFS = "migration_notice"

    /** true = 有一次迁移刚发生、还没告诉过用户 */
    private const val KEY_RESTART_PENDING = "restart_notice_pending"

    /** App 启动时调用一次（非阻塞：只在后台线程上跑 root shell） */
    fun ensureMigratedOnce() {
        if (!started.compareAndSet(false, true)) return
        val t = Thread({
            runCatching { migrateNow() }
                .onSuccess { if (it.isNotEmpty()) Log.i(TAG, "迁移: $it") }
                .onFailure { Log.w(TAG, "迁移失败（旧目录保留，不影响功能）: ", it) }
        }, "ksu-legacy-migrate")
        t.isDaemon = true
        t.start()
    }

    /**
     * 首页界面的**实时**状态：迁移刚发生时，界面要能**立刻**出现提示卡。
     *
     * 为什么不能只靠"回到前台时读一次 prefs"：迁移是在启动后几秒才在后台线程跑完的，
     * 那时界面早就 RESUMED、不会再触发一次读取 —— 卡片就永远不出现。
     * 所以 [markRestartNoticePending] 除了写 prefs，还往这个 flow 里推一次。
     */
    private val restartPendingFlow = MutableStateFlow(false)

    val restartPending: StateFlow<Boolean> = restartPendingFlow.asStateFlow()

    /** 组合首次进入时，把 prefs 里**上次会话遗留**的"待提示"同步进 flow。 */
    fun refreshRestartPending(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
            restartPendingFlow.value = prefs.getBoolean(KEY_RESTART_PENDING, false)
        }
    }

    /**
     * 用户点掉提示卡 → 清标志，**以后不再提示**（"不超过一次"）。
     */
    fun acknowledgeRestartNotice(context: Context) {
        runCatching {
            context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RESTART_PENDING, false).apply()
            restartPendingFlow.value = false
        }
    }

    /**
     * 真跑一次。返回人类可读的结果（同时写日志）。
     * 需要 root；拿不到 root / 拿不到自带 ksud 就什么都不做（留给 ksud 开机时自己搬）。
     *
     * 🔴 D3(v2.2)：`migrate()` 那一次如果**真的搬了东西**（复制/修复/改名/删旧目录），
     * 就落一个"待提示重启"标志 —— 因为**内核只在开机时读一次授权名单**，
     * 不重启的话内核内存里那份还是旧的，而**这次授权新 App 会把刚迁来的旧名单覆盖掉**。
     */
    fun migrateNow(): String = runCatching {
        val ksud = getKsuDaemonPath()
        if (!File(ksud).exists()) {
            return@runCatching "SKIP：找不到本 APK 自带的 ksud（$ksud），不迁移"
        }

        // ── 第一步：门控预检（ksud 内部会真的去问内核"你在读写哪个目录"）──
        val probeOut = runKsud(ksud, "migrate --probe-only")
        val verdict = probeOut.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("KERNEL_DATADIR=") }
            ?.substringAfter('=')
            ?.trim()

        val body = if (verdict != VERDICT_NEW_DATADIR) {
            // 旧内核 / 拿不准 → **一个字节都不搬**（这是刻意的：宁可晚点迁）
            "SKIP：探测=$verdict（不是新内核/拿不准）→ $LEGACY_DIR 保持原样、$NEW_DIR 不建 | $probeOut"
        } else {
            // ── 第二步：确认新内核在跑，才让 ksud 搬（它内部还有复制→校验→才删旧的）──
            val out = runKsud(ksud, "migrate")
            // 🔴 D3：**只在真的搬过东西**时落"待提示重启"标志（解析 ksud 那一行摘要）
            if (migrationChangedSomething(out)) {
                markRestartNoticePending()
            }
            // ksud 若在迁移中采纳了旧目录独有的授权，同样要提示"请重启一次"
            parseMachineValue(out, "ALLOWLIST_ADOPTED")?.let {
                if (it > 0) markRestartNoticePending()
            }
            "探测=$verdict | $out"
        }

        // ── v2.9：兼容软链兜底 + 迁移体检（**故意放在 ksud 之后**）──────────────
        // 为什么放在后面：ksud 在自己进程开头就保证过软链了（带门控），这里跑在它之后
        // 才能**观察到最终状态**并打进日志（真机排查就靠这一行）。
        // ⚠️ 上面两条"SKIP"路径也要跑：老用户（旧路径上什么都没有）正是靠它补软链。
        "$body | ${ensureCompatLink()}"
    }.getOrElse { "执行失败: ${it.message}" }

    /** 读 ksud 打印的 `KEY=数字` 机器可读行（读不到返回 null，绝不猜） */
    private fun parseMachineValue(out: String, key: String): Int? = out.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toIntOrNull()

    /**
     * 🟢 v2.9：**幂等地**保证兼容软链 `/data/adb/ksu` → `/data/adb/sevenk` 就位，
     * 并返回一行"迁移体检"结果（同时由调用方写进日志）。
     *
     * 一次 root shell 里做完三件事（**只读 + 最多建一条软链**，不删任何东西）：
     *   1. 看旧路径现在是什么形态（不存在 / 软链指向哪 / 真实目录有几项 / 别的类型）；
     *   2. **只有在"旧路径上什么都没有、且新目录是真实目录"时**才 `ln -s`；
     *   3. 体检新目录：三个关键文件（`.allowlist` / `stealth` / `stealth_code`）
     *      **在不在、多大**，以及模块目录里有多少项 —— **只 stat，不读内容**。
     *
     * 为什么 App 侧不处理"真实目录"那两种形态：要不要把有内容的老目录改名腾出路径，
     * 必须先确认"新内核在跑"（要发内核 ioctl，App 这边没有通道）——
     * 那是 ksud `ensure_compat_link_at` 里带门控的实现，见 `userspace/ksud/src/migrate.rs`。
     *
     * 任何失败都只是返回一行字，**不抛异常**（调用方还有 runCatching 兜底）。
     */
    fun ensureCompatLink(): String = runCatching {
        val script = buildString {
            append("L=$LEGACY_DIR; N=$NEW_DIR; ")
            // ① 旧路径现在是什么
            append("if [ -L \"\$L\" ]; then STATE=\"symlink->\$(readlink \"\$L\")\"; ")
            append("elif [ -d \"\$L\" ]; then STATE=\"real-dir(entries=\$(ls -A \"\$L\" 2>/dev/null | wc -l))\"; ")
            append("elif [ -e \"\$L\" ]; then STATE=\"other-type\"; else STATE=\"absent\"; fi; ")
            // ② 只补"什么都没占"的那种：建软链
            append("if [ \"\$STATE\" = absent ] && [ -d \"\$N\" ]; then ")
            append("if ln -s \"\$N\" \"\$L\" 2>/dev/null; then STATE=\"created->\$N\"; ")
            append("else STATE=\"absent(create-failed)\"; fi; fi; ")
            // ③ 迁移体检：关键文件在不在、多大；模块有几个（只 stat，不读内容）
            append("K=\"\"; for f in .allowlist stealth stealth_code; do ")
            append("if [ -e \"\$N/\$f\" ]; then K=\"\$K \$f=\$(stat -c%s \"\$N/\$f\" 2>/dev/null)\"; ")
            append("else K=\"\$K \$f=MISSING\"; fi; done; ")
            append("M=\$(ls -1 /data/adb/modules 2>/dev/null | wc -l); ")
            append("echo \"兼容软链=\$STATE | 新目录体检:\$K | 模块数=\$M\"")
        }
        runRootScript(script)
    }.getOrElse { "兼容软链检查失败: ${it.message}" }

    /**
     * 从 ksud `migrate` 的摘要里判断"这次到底动没动数据"（🔴 D3）。
     *
     * ksud 的摘要只有两种形态（见 `userspace/ksud/src/migrate.rs::Report::summary`）：
     *   · 什么都没做：`旧目录不存在，无需迁移` / `门控结论缓存命中（…）⇒ 本次不探测/不体检/不写任何文件`
     *     / `本次不迁移：…`
     *   · 做了事：`原子改名成功：…`，或者
     *     `复制 N 条、已存在且一致 M 条、冲突 X 条、失败 Y 条、关键文件修复 Z 条；旧目录已删除|保留`
     *
     * 判据刻意**只认"N>0 或 Z>0 或 已删除 或 原子改名"**：
     *   · `已存在且一致`（already）不算 —— 那说明数据早就在新目录了，内核下次开机自然读到，
     *     没必要为它提醒用户重启；
     *   · 识别不出来（ksud 改了文案）→ 按"没动过"处理，**宁可少提示一次，也不误报**。
     */
    private fun migrationChangedSomething(out: String): Boolean {
        if (out.contains("原子改名成功")) return true
        if (out.contains("旧目录已删除")) return true
        val copied = Regex("""复制\s*(\d+)\s*条""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val repaired = Regex("""关键文件修复\s*(\d+)\s*条""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return copied > 0 || repaired > 0
    }

    /** 落"待提示重启"标志（后台线程调用，`apply()` 异步落盘，不阻塞） */
    private fun markRestartNoticePending() {
        runCatching {
            ksuApp.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RESTART_PENDING, true).apply()
            // 同时推给界面（迁移是启动后几秒才跑完的，界面不会再自己读一次 prefs）
            restartPendingFlow.value = true
            Log.i(TAG, "已迁移数据 → 置「请重启一次」提示标志")
        }.onFailure { Log.w(TAG, "置提示标志失败（忽略）: ${it.javaClass.simpleName}") }
    }

    /** 跑一条 ksud 子命令（root shell），返回 stdout+stderr 合并后的文本 */
    private fun runKsud(ksudPath: String, args: String): String =
        runRootScript("\"$ksudPath\" $args 2>&1")

    /**
     * 跑一段脚本（root shell），返回 stdout+stderr 合并后的文本。
     *
     * 与 [runKsud] 共用同一条路径：**失败也返回一行字**（带退出码），绝不抛异常。
     */
    private fun runRootScript(script: String): String {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = getRootShell().newJob().add(script).to(out, err).exec()
        val text = buildString {
            append(out.joinToString("\n").trim())
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("[stderr] ").append(err.joinToString("\n").trim())
            }
        }.trim()
        return text.ifEmpty { "(无输出，退出码 ${r.code})" }
    }
}

/**
 * 首页用的状态：要不要显示「数据已迁移，请重启一次」提示卡（🔴 D3，v2.2）。
 *
 * 两条来源合起来看：
 *   · 首次进入组合时把 prefs 里**上次会话遗留**的标志读进来（用户上次没点掉）；
 *   · 本次会话里后台线程刚迁完 → [LegacyDataMigration.markRestartNoticePending]
 *     直接推 flow，界面**当场**就能出现卡片。
 */
@Composable
fun rememberMigrationRestartPending(): Boolean {
    val context = LocalContext.current
    val pending by LegacyDataMigration.restartPending.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LegacyDataMigration.refreshRestartPending(context) }
    return pending
}
