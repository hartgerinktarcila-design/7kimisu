package com.sevenk.core.ui.util

import android.util.Log

/**
 * App 侧与「网页管理器」的唯一通道。
 *
 * ⚠️ 网页服务本身**不在 App 里**，跑在 **ksud（root 守护进程）** 里。
 * 原因（2026-09-16 真实教训）：写在 App 里时，用户把管理器从最近任务划掉、
 * 或用系统"一键清理"，进程一死端口就关，浏览器直接"拒绝连接"。
 * 参考另一个管理器（DickSU）的做法后改成 ksud 托管：
 *   · 划掉 App、一键清理都不影响（ksud 不是 App 进程）
 *   · 重启手机后由 ksud 的 post-fs-data 钩子自动拉起
 *   · 不需要前台服务、不需要常驻通知
 *
 * 所以 App 这边只剩三件事：**开关**、**取地址（含访问密钥）**、**把地址给浏览器**。
 *
 * 🔐 鉴权（2026-09-18）：服务端需要**专属密钥**，地址形如
 * `http://127.0.0.1:18427/x7k9f/<64 位十六进制密钥>`。密钥由 ksud 生成并存在
 * `/data/adb/sevenk/webadmin.conf`（0600），**App 这边只在内存里过一手**，
 * 不写进任何日志、不写进 prefs、不作为命令行参数传（`ps` 全机可读）。
 *
 * 对应命令行（在设备上 root shell 里可以直接用）：
 * ```
 * ksud webadmin on           # 打开并拉起常驻进程（顺带打印带密钥的地址）
 * ksud webadmin off          # 关闭（密钥保留，下次打开还是同一个）
 * ksud webadmin url          # 打印访问地址（含访问密钥）
 * ksud webadmin status       # enabled / 端口 / 密钥是否已设置（**不含密钥本体**）
 * ksud webadmin reset-token  # 换一把密钥，旧链接立即失效
 * ```
 */
object WebAdminCli {

    private const val TAG = "7kkernel-webadmin"

    /**
     * 🔐 把输出里可能出现的**访问密钥**打码。
     *
     * 为什么必须做：`ksud webadmin on` / `url` 的 stdout 就是**带密钥的完整地址**
     * （`http://127.0.0.1:18427/x7k9f/<64 位十六进制>`）。旧代码把这段原文 `Log.i` 进了
     * logcat —— 而 logcat 在部分机型上**别的 App 也能读**，等于与本文件 20~21 行
     * "密钥不写进任何日志"的承诺自相矛盾（拿到密钥 = 本机任意 App 能自授 root）。
     * 这里把所有 `/{前缀}/{长十六进制}` 形式统一抹成 `<已隐藏>`，日志只保留形状。
     */
    private fun redact(s: String): String =
        s.replace(Regex("(/x7k9f/)[0-9a-fA-F]{16,}"), "$1<已隐藏>")

    /** 跑一条 ksud webadmin 子命令，返回 stdout（失败返回空串） */
    private fun run(args: String): String = runCatching {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = getRootShell().newJob()
            .add("${getKsuDaemonPath()} webadmin $args")
            .to(out, err)
            .exec()
        if (result.code != 0) {
            Log.w(TAG, "ksud webadmin $args 退出码 ${result.code}: ${redact(err.joinToString("\n"))}")
        }
        out.joinToString("\n").trim()
    }.getOrElse {
        Log.w(TAG, "ksud webadmin $args 执行失败", it)
        ""
    }

    /** 开关：打开会同时把 ksud 常驻进程拉起来 */
    fun setEnabled(enable: Boolean): Boolean {
        val out = run(if (enable) "on" else "off")
        // ⚠️ `on` 的 stdout 里带密钥 ⇒ 必须打码（见 redact 的注释）
        Log.i(TAG, "webadmin ${if (enable) "on" else "off"} → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** 访问地址（**含 64 位访问密钥**）；ksud 没装/没起来时返回空串 */
    fun url(): String {
        val out = run("url")
        return if (out.startsWith("http://")) out else ""
    }

    /**
     * 换一把访问密钥，返回**新的完整地址**（失败返回空串）。
     *
     * ⚠️ 密钥只出现在方法的返回值里（走 root shell 的 stdout），
     * 不作为命令行参数传（`ps` 里看不到），也不写日志。
     */
    fun resetToken(): String {
        val out = run("reset-token")
        return out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("http://") } ?: ""
    }

    /** 状态原文（诊断用，设置页直接显示） */
    fun status(): String = run("status")

    /**
     * 把 App 里的开关状态同步给 ksud（每次打开 App 都跑一次）。
     *
     * 两件事必须做：
     *  1. 开关状态存在 App 的 prefs 里，而服务读的是 `/data/adb/sevenk/webadmin.conf`。
     *     用户升级安装后开关本来就是"开"的，**不会再去拨一次** —— 不同步就永远不生效。
     *  2. 升级 APK 后 /data/adb/sevenk/bin/ksud 是新的，但正在跑的旧守护进程不会自己换。
     *
     * ⚠️ 用 **sync** 而不是 restart（2026-09-17 改）：
     *    App 每次 `onCreate`（**开关隐身引起的界面重建也算**）都会走这里，
     *    原来固定 restart → 服务被停掉再拉起，有约 1 秒刷不了网页，而其实
     *    绝大多数时候 ksud 根本没换过。`sync` 会让 ksud 自己比一比
     *    "跑着的二进制"和"盘上的二进制"：一样就什么都不做，不一样才重启。
     *    （设置页那个「重启」按钮仍然用 restart —— 那是给用户手动救活用的，必须真重启。）
     */
    fun syncPref(enabled: Boolean): Boolean = if (enabled) sync() else setEnabled(false)

    /** 确保在跑、且跑的是当前这份 ksud；已经是同一个就不动（不会断服务） */
    fun sync(): Boolean {
        val out = run("sync")
        Log.i(TAG, "webadmin sync → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** 重启守护进程（先停再起），用于升级后让新 ksud 生效 */
    fun restart(): Boolean {
        val out = run("restart")
        Log.i(TAG, "webadmin restart → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** 设备端诊断：一条命令把所有关键信息抓回来（用户复制给我们就行） */
    fun diagnose(): String = runRaw(
        buildString {
            append("echo '=== 已安装的 ksud ==='; ")
            append("ls -l /data/adb/sevenk/bin/ksud /data/adb/ksud 2>&1; ")
            append("echo '=== ksud webadmin status ==='; ")
            append("/data/adb/sevenk/bin/ksud webadmin status 2>&1; ")
            append("echo '=== 配置 webadmin.conf（密钥已打码）==='; ")
            // 🔐 诊断报告是给用户**复制发出去**的，绝不能把访问密钥一起带走
            //    （拿到密钥 = 本机拿到 root）。所以这里把 token 那一行替换掉。
            append("sed 's/^\\(token=\\).*/\\1<已隐藏>/' /data/adb/sevenk/webadmin.conf 2>&1; ")
            append("echo '=== 状态 webadmin.status ==='; ")
            append("cat /data/adb/sevenk/webadmin.status 2>&1; ")
            append("echo '=== pid 文件 ==='; ")
            append("cat /data/adb/sevenk/webadmin.pid 2>&1; ")
            append("echo '=== 守护进程 ppid（1 = 已脱离会话）==='; ")
            append("ps -A -o PID,PPID,ARGS 2>/dev/null | grep 'ksud webadmin serve' | grep -v grep; ")
            append("echo '=== ksud 相关进程 ==='; ")
            append("ps -A -o PID,ARGS 2>/dev/null | grep -i ksud | head -8; ")
            append("echo '=== 谁在听 18427 ==='; ")
            append("(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep 18427; ")
            append("echo '=== 日志尾部 ==='; ")
            append("tail -n 25 /data/adb/sevenk/webadmin.log 2>&1")
        }
    )

    /** 跑一段自定义脚本（root shell），返回输出 */
    private fun runRaw(script: String): String = runCatching {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = getRootShell().newJob().add(script).to(out, err).exec()
        buildString {
            append(out.joinToString("\n"))
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("[stderr] ").append(err.joinToString("\n"))
            }
            if (isEmpty()) append("(无输出，退出码 ${r.code})")
        }.trim()
    }.getOrElse { "执行失败: ${it.message}" }

    /** 用本机浏览器打开（地址由程序给，省得用户手抄 —— 抄漏一个字符就 404） */
    fun openInBrowser(context: android.content.Context, url: String): String? {
        if (url.isBlank()) return "地址还没读到（ksud 没起来？）"
        return runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    /** 复制完整地址到剪贴板 */
    fun copyUrl(context: android.content.Context, url: String): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("7kimisu 网页管理器", url))
        true
    }.getOrDefault(false)
}
