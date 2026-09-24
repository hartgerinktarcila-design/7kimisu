package com.sevenk.core.core.utils

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 🟡（v2.14）**Application 级**的后台单线程执行器。
 *
 * 为什么需要它：以前"启动时顺手跑一下"的那些地方都是就地
 * `Executors.newSingleThreadExecutor()` —— 每调用一次就新建一个线程池，而且**从来不 shutdown**：
 *   · `MainActivity.onCreate` 里 3 处（转屏、换语言、切深浅色都会重建 Activity）→ 每次漏一个；
 *   · 设置页每点一次按钮（恢复默认壁纸 / 写密令）也要漏一个。
 * 线程池的核心线程默认**不是 daemon**，各自还挂着一条任务队列 —— 长期用下来就是白占内存。
 *
 * 这里全局共用一个 **daemon 单线程池**：
 *   · daemon ⇒ 进程退出不会被它拖住，所以**不需要任何人去 shutdown**；
 *   · 单线程 ⇒ 这些任务都是"一下子就好"的小 IO / root 调用，串行反而少几次并发起 shell；
 *   · 全程 `runCatching` 由调用方负责（和以前一样），这里不吞异常。
 */
object AppExecutors {

    /** 串行后台执行器（daemon）。适合"启动时顺手做一次的 IO / root 调用"。 */
    val io: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sevenk-bg").apply { isDaemon = true }
    }
}
