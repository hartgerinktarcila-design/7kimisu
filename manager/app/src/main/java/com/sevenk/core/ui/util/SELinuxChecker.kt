package com.sevenk.core.ui.util

import android.util.Log
import com.topjohnwu.superuser.Shell

private const val TAG = "SELinuxChecker"

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended).
 */
fun getSELinuxStatusRaw(): String {
    // ⚠️ 2026-09-19 修:这里以前是**裸**的 `Shell.Builder.create().build("sh")`。
    //    它在首页状态构建里被调用(以前还在主线程上),一旦起不了 shell 就抛
    //    `NoShellException` → 未捕获 → 闪退;即使不崩也是最坏 1.5s 的主线程阻塞(ANR)。
    //    现在整段包 runCatching:读不到就返回 "Unknown"(界面本来就认这个值),
    //    调用方拿到的永远是一个字符串,不会有异常冒出去。
    return runCatching {
        val shell = Shell.Builder.create().build("sh")

        val stdoutList = ArrayList<String>()
        val stderrList = ArrayList<String>()
        val result = shell.use {
            it.newJob().add("getenforce").to(stdoutList, stderrList).exec()
        }
        val stdout = stdoutList.joinToString("\n").trim()
        val stderr = stderrList.joinToString("\n").trim()

        if (result.isSuccess) {
            return@runCatching when (stdout) {
                "Enforcing", "Permissive", "Disabled" -> stdout
                else -> "Unknown"
            }
        }

        if (stderr.endsWith("Permission denied")) {
            "Enforcing"
        } else {
            "Unknown"
        }
    }.getOrElse { e ->
        Log.w(TAG, "读取 SELinux 状态失败,按 Unknown 处理: ", e)
        "Unknown"
    }
}
