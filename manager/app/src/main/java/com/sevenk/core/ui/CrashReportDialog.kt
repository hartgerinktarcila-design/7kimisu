package com.sevenk.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.sevenk.core.CrashReporter

/** 等后台抓取落盘的最长等待：轮询次数 × 间隔 = 20 秒。 */
private const val REPORT_POLL_TIMES = 20
private const val REPORT_POLL_INTERVAL_MS = 1000L

/** 报告正文最多显示 / 复制的高度上限，超出部分靠滚动看。 */
private val REPORT_MAX_HEIGHT = 360.dp

/**
 * 「上次打开时崩溃了」弹窗（2026-09-19 新增）。
 *
 * 只在 `filesDir/last_crash.txt` 有内容时才弹；内容来自
 * [com.sevenk.core.KernelSUApplication] 里的崩溃上报（Java 异常栈 / tombstone / logcat）。
 *
 * 两个来源的时序不一样，所以这里要分两步读：
 *   - Java 崩溃：上个进程崩溃当刻就写好了 → 打开 App 立刻能读到 → 立刻弹；
 *   - 原生崩溃：由**本次启动**的后台线程去抓（要起 root shell、读 tombstone/logcat），
 *     几秒后才落盘 → 这里给一段有限的轮询窗口，等它出现。
 * 等不到就当没有，安静收场，绝不打扰用户。
 *
 * 约定：调用方把它放在 `setContent { }` 的**最前面**（隐身模式下也要弹），
 * 且只需要调用一次；它自己不占布局（Dialog 是独立窗口）。
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 首帧不读盘：读文件放到 IO 线程，读完再决定弹不弹
        var text = readReport(context)
        var waited = 0
        while (text == null && waited < REPORT_POLL_TIMES) {
            delay(REPORT_POLL_INTERVAL_MS)
            text = readReport(context)
            waited++
        }
        report = text
    }

    val text = report ?: return

    AlertDialog(
        onDismissRequest = {
            // 点外面关掉也算「已读」：删文件 + 关闭，避免每次启动都弹
            report = null
            CrashReporter.clear(context)
        },
        title = { Text("上次打开时崩溃了") },
        text = {
            Text(
                text = text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = REPORT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("7kimisu-crash", text))
                    }
                    runCatching {
                        Toast.makeText(context, "已复制，去粘贴发给我们即可", Toast.LENGTH_SHORT).show()
                    }
                    // 已经复制到手了，就把文件删掉，避免每次启动都弹（弹窗里仍能继续看）
                    CrashReporter.clear(context)
                },
            ) {
                Text("复制")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    report = null
                    CrashReporter.clear(context)
                },
            ) {
                Text("关闭")
            }
        },
    )
}

/** 读报告；整段 runCatching 在 CrashReporter 里，这里再放 IO 线程，绝不卡主线程。 */
private suspend fun readReport(context: Context): String? =
    withContext(Dispatchers.IO) { CrashReporter.read(context) }
