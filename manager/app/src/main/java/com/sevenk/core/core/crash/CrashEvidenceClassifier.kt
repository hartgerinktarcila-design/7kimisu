package com.sevenk.core.core.crash

/**
 * 「上次会话没正常收尾」时，崩溃证据的**分类器**。
 *
 * ⚠️ 这个文件**刻意不 import 任何 Android 类**（只用一个正则），
 * 这样它能在**宿主机（JVM）上直接跑**：回归用例见
 * `scripts/repro-crash-classifier/`（7 个用例，含用户真机 tombstone 的形状）。
 * 生产代码只是把 `BuildConfig.APPLICATION_ID` 传进来。
 *
 * ══════════════════════════════════════════════════════════════════
 * 判据（v2.20 已简化到最小 —— 用户要求"退回简单形态"）
 * ══════════════════════════════════════════════════════════════════
 * 三条，缺一不可：
 *   ① 证据里**必须**有真崩溃特征（`FATAL EXCEPTION` / SIGSEGV / SIGSYS / tombstone…），
 *      否则一律 [Verdict.NOT_OURS]（系统杀后台就是这个分支）；
 *   ② 而且**必须点名本 App 这个进程**（`Process: <包名>` / `>>> <包名> <<<` /
 *      `Cmdline: <包名>` / `(<包名>)`）→ [Verdict.OUR_CRASH]（写报告 + 下次启动弹窗）；
 *   ③ 不点名本进程、但带着我们的库名 / seccomp 痕迹 → [Verdict.KERNEL_PROBE_BLOCKED]
 *      （**只记一行日志**，不落盘、不弹窗）；其余 → [Verdict.NOT_OURS]，什么都不做。
 *
 * ══════════════════════════════════════════════════════════════════
 * ②「必须点名本进程」为什么是硬要求（v2.14 的真实误报）
 * ══════════════════════════════════════════════════════════════════
 * `libksud.so feature check <name>` 是**子进程**：它探测内核时会发一个 `reboot(2)` 魔数
 * 系统调用，在开启 seccomp 且把 reboot 配成 KILL 的 ROM（vivo/OriginOS、部分 OPPO、MIUI…）
 * 上会被内核**直接杀掉**（SIGSYS / signal 31）。**App 进程本身活得好好的**。
 *
 * 旧判据是「出现 SIGSYS + 出现 libksud/libsevenk ⇒ 算我们崩了」⇒ 写 `last_crash.txt` +
 * 下次启动弹「崩溃上报」⇒ 用户以为闪退，其实没崩。所以现在**只有点名本 App 进程**的证据
 * 才算我们崩了；只有库名 / seccomp 痕迹的一律 [Verdict.KERNEL_PROBE_BLOCKED]（只记日志）。
 * ⚠️ 反过来，**App 进程自己**被同一条 SIGSYS 杀掉时必须照旧上报 —— 靠的就是"点名本进程"。
 */
object CrashEvidenceClassifier {

    /**
     * 「上次会话没正常收尾」的自动抓取结论。
     */
    enum class Verdict {
        /** 真·**本 App 进程**崩了：写 `last_crash.txt`，下次启动弹「崩溃上报」。 */
        OUR_CRASH,

        /**
         * 只是 **ksud / 内核探测的子进程**被系统拦下或杀掉（典型：SIGSYS/seccomp）。
         * App 进程没死 ⇒ **只写一行日志**，不落文件、不弹窗。
         */
        KERNEL_PROBE_BLOCKED,

        /** 什么都算不上（系统杀后台、别人的崩溃……）⇒ 什么都不做。 */
        NOT_OURS,
    }

    /**
     * 抓取阶段的过滤：这一行要不要收进证据里。
     *
     * ⚠️ **只影响"报告里包含哪些行"，不影响 [classify] 的判定**。
     * 宁松勿漏：多抓几行只是报告长一点。其中 `#NN pc `（tombstone 帧号 + pc 寄存器）
     * 一条不能少 —— 否则 tombstone 的**栈顶**（"谁调用了 abort"）会因为一个关键词都不含
     * 而被整行丢掉，用户复制给我们的报告里就只剩我们自己库里那几帧（v2.16 修过的"丢栈"）。
     */
    private val CRASH_HINT_REGEX = Regex(
        "FATAL EXCEPTION|SIGSEGV|SIGSYS|SIGABRT|backtrace|libsevenk|libksud|#\\d+\\s+pc\\s",
        RegexOption.IGNORE_CASE,
    )

    /** 这一行要不要收进证据里（tombstone 行 / logcat 行共用）。 */
    fun isCrashHintLine(line: String): Boolean = CRASH_HINT_REGEX.containsMatchIn(line)

    /**
     * ① 「**真**崩溃特征」—— 必须命中其中之一，否则一律当成「没崩」。
     *
     * ⚠️ 只出现我们库名（`libsevenk` 之类）**不算**：那是"谁崩了"的问题，
     * 由下面 [ourProcessRegex] 决定。
     */
    private val CRASH_SIGNATURE_REGEX = Regex(
        "FATAL EXCEPTION|SIGSEGV|SIGSYS|SIGABRT|signal |backtrace|tombstone|/data/tombstones/",
        RegexOption.IGNORE_CASE,
    )

    /**
     * ② 「崩的是**本 App 这个进程**」的身份特征 —— 收紧后的**唯一**硬判据。
     *
     * 四种写法，每一种都直接点名包名：
     *   · Java 未捕获异常：`Process: com.sevenk.core, PID: 1234`
     *   · tombstone 进程头：`>>> com.sevenk.core <<<`
     *   · tombstone：`Cmdline: com.sevenk.core`
     *   · logcat 的 `in tid 1234 (com.sevenk.core)`
     * 只有 `libksud.so` / `libsevenk.so` / `SIGSYS` 而**没有**本包名的，一律不算 App 崩溃。
     *
     * ⚠️ 为什么不用 pid 比对：崩溃那次的 pid 属于**上一个进程**，本次启动的
     * `Process.myPid()` 是另一个进程 —— 跨会话比 pid 必然对不上。所以只认包名，
     * 而 tombstone 的进程头（`>>> <包名> <<<`）本身就把 pid/cmdline 一起点名了。
     */
    private fun ourProcessRegex(appId: String): Regex = Regex(
        "Process:\\s*${Regex.escape(appId)}" +
            "|>>>\\s*${Regex.escape(appId)}\\s*<<<" +
            "|Cmdline:\\s*${Regex.escape(appId)}" +
            "|\\(${Regex.escape(appId)}\\)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * ③ 「内核探测被系统拦下」的特征（**不是** App 崩溃）。
     * 命中这一类时：只记日志，既不落 `last_crash.txt`、也不弹「崩溃上报」。
     */
    private val KERNEL_PROBE_BLOCKED_REGEX = Regex(
        "SIGSYS|SYS_SECCOMP|blocked by seccomp|libksud|libsevenk|kernelsu",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 判定「上次会话没正常收尾」到底是哪一类。
     *
     * @param text  抓到的证据全文（tombstone 片段 + logcat 片段拼起来的那份）
     * @param appId 本 App 的包名（生产代码传 `BuildConfig.APPLICATION_ID`）
     */
    fun classify(text: String, appId: String): Verdict {
        if (text.isBlank()) return Verdict.NOT_OURS
        // ① 必须命中真崩溃特征（否则不管里面有多少 "libsevenk" 都不算崩溃）
        if (!CRASH_SIGNATURE_REGEX.containsMatchIn(text)) return Verdict.NOT_OURS
        // ② 必须点名**本 App 进程** —— 这一条把"子进程被 seccomp 杀"挡在外面
        if (ourProcessRegex(appId).containsMatchIn(text)) return Verdict.OUR_CRASH
        // ③ 没点名本进程，但明显是我们的 ksud / 内核探测被系统拦下了 → 不是 App 的错
        if (KERNEL_PROBE_BLOCKED_REGEX.containsMatchIn(text)) {
            return Verdict.KERNEL_PROBE_BLOCKED
        }
        return Verdict.NOT_OURS
    }
}
