package com.sevenk.core.ui.screen.executemoduleaction

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.launch
import com.sevenk.core.R
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.navigation3.LocalNavigator

@Composable
fun ExecuteModuleActionScreen(moduleId: String, fromShortcut: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val logContent = remember { StringBuilder() }
    var isComplete by rememberSaveable { mutableStateOf(false) }
    val uiMode = LocalUiMode.current
    val snackbarHost = remember { SnackbarHostState() }
    val exitExecute = {
        if (fromShortcut && activity != null) {
            activity.finishAndRemoveTask()
        } else {
            navigator.pop()
        }
    }

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Always auto disable from shortcuts
    LaunchedEffect(isComplete) {
        if (isComplete) {
            if (fromShortcut) {
                exitExecute()
            }
        }
    }

    ExecuteModuleActionEffect(
        moduleId = moduleId,
        text = text,
        logContent = logContent,
        fromShortcut = fromShortcut,
        onTextUpdate = { text = it },
        onComplete = { isComplete = true },
        onExit = exitExecute
    )

    val state = ExecuteModuleActionUiState(
        text = text,
        isComplete = isComplete,
    )
    val actions = ExecuteModuleActionScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        // 🟠（v2.19）失败文案在组合期取好（saveLog 不是 @Composable，只能传字符串进去）
        onSaveLog = saveLog(logContent, scope, stringResource(R.string.log_save_failed)) { showMessage(it) },
        onClose = exitExecute,
    )

    when (uiMode) {
        UiMode.Miuix -> ExecuteModuleActionScreenMiuix(state, actions)
        UiMode.Material -> ExecuteModuleActionScreenMaterial(state, actions, snackbarHost)
    }
}
