package com.sevenk.core.ui.screen.executemoduleaction

import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.R
import com.sevenk.core.data.repository.ModuleRepositoryImpl
import com.sevenk.core.ui.util.runModuleAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExecuteModuleActionEffect(
    moduleId: String,
    text: String,
    logContent: StringBuilder,
    fromShortcut: Boolean,
    onTextUpdate: (String) -> Unit,
    onComplete: () -> Unit = {},
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val noModule = stringResource(R.string.no_such_module)
    val moduleUnavailable = stringResource(R.string.module_unavailable)
    val moduleActionSuccess = stringResource(R.string.module_action_success)

    LaunchedEffect(Unit) {
        if (text.isNotEmpty()) {
            return@LaunchedEffect
        }
        val repo = ModuleRepositoryImpl()
        val modules = repo.getModules().getOrDefault(emptyList())
        val moduleInfo = modules.find { info -> info.id == moduleId }
        if (moduleInfo == null) {
            Toast.makeText(context, noModule.format(moduleId), Toast.LENGTH_SHORT).show()
            onExit()
            return@LaunchedEffect
        }
        if (!moduleInfo.hasActionScript) {
            onExit()
            return@LaunchedEffect
        }
        if (!moduleInfo.enabled || moduleInfo.update || moduleInfo.remove) {
            Toast.makeText(context, moduleUnavailable.format(moduleInfo.name), Toast.LENGTH_SHORT).show()
            onExit()
            return@LaunchedEffect
        }
        var actionResult: Boolean
        var currentText = text
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            runModuleAction(
                moduleId = moduleId,
                onStdout = {
                    val tempText = "$it\n"
                    if (tempText.startsWith("[H[J")) { // clear command
                        currentText = tempText.substring(6)
                    } else {
                        currentText += tempText
                    }
                    mainHandler.post {
                        onTextUpdate(currentText)
                    }
                    logContent.append(it).append("\n")
                },
                onStderr = {
                    logContent.append(it).append("\n")
                }
            ).let {
                actionResult = it
            }
        }
        if (actionResult && fromShortcut) {
            Toast.makeText(
                context,
                moduleActionSuccess,
                Toast.LENGTH_SHORT
            ).show()
        }
        onComplete()
    }
}

fun saveLog(
    logContent: StringBuilder,
    scope: CoroutineScope,
    failMessage: String,
    showMessage: (String) -> Unit
): () -> Unit {
    return {
        scope.launch {
            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val date = format.format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "KernelSU_module_action_log_${date}.log"
            )
            // 🔴（v2.19）写成功才报"已保存"。旧写法 `writeText` 之后**无条件**报成功，
            // 存储不可写 / 磁盘满 / 没权限时用户以为日志存下来了，实际什么都没写。
            // 现在失败弹新增的 log_save_failed（其余语言回退到默认英文）。
            runCatching { file.writeText(logContent.toString()) }
                .onSuccess { showMessage("Log saved to ${file.absolutePath}") }
                .onFailure { showMessage(failMessage) }
        }
    }
}
