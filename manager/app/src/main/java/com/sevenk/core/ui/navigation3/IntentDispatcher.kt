package com.sevenk.core.ui.navigation3

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import kotlinx.coroutines.channels.ReceiveChannel
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.screen.flash.FlashIt
import com.sevenk.core.ui.util.DownloadService
import com.sevenk.core.ui.util.getFileName
import com.sevenk.core.ui.webui.WebUIActivity

private const val SCHEME_KSU = "ksu"
private const val HOST_ACTION = "action"
private const val HOST_WEBUI = "webui"
private const val PARAM_ID = "id"
private const val PARAM_TOKEN = "token"

/**
 * Resolved intent action to execute after validation.
 */
private sealed interface PendingAction {
    /** Install module(s) from URI — triggered by DownloadService notification or ZIP file open. */
    data class InstallModule(
        val uri: Uri,
        val displayName: String,
        val requiresConfirmation: Boolean,
    ) : PendingAction {
        companion object {
            val InstallModuleSaver = listSaver<InstallModule?, Any>(
                save = { module ->
                    if (module == null) {
                        emptyList()
                    } else {
                        listOf(
                            module.uri,
                            module.displayName,
                            module.requiresConfirmation
                        )
                    }
                },
                restore = { list ->
                    if (list.isEmpty()) {
                        null
                    } else {
                        InstallModule(
                            uri = list[0] as Uri,
                            displayName = list[1] as String,
                            requiresConfirmation = list[2] as Boolean
                        )
                    }
                }
            )
        }
    }

    /** Execute a module's action script — triggered by shortcut or deep link. */
    data class ExecuteAction(val moduleId: String) : PendingAction

    /** Open a module's WebUI — triggered by shortcut. */
    data class OpenWebUI(val moduleId: String) : PendingAction
}

private sealed interface KsuDeepLink {
    data class Action(val moduleId: String) : KsuDeepLink
    data class WebUi(val moduleId: String) : KsuDeepLink
}

private fun buildInternalWebUiUri(moduleId: String): Uri {
    return Uri.Builder()
        .scheme(SCHEME_KSU)
        .authority(HOST_WEBUI)
        .appendQueryParameter(PARAM_ID, moduleId)
        .build()
}

fun getDisplayName(uri: Uri): String {
    return uri.getFileName(ksuApp) ?: uri.lastPathSegment ?: "Unknown"
}

/**
 * Resolve an intent snapshot into a [PendingAction].
 * Returns null if the intent carries no recognized action.
 */
private fun resolveIntent(intent: Intent): PendingAction? {
    // DownloadService notification: install module
    if (intent.action == DownloadService.ACTION_INSTALL_MODULE) {
        val token = intent.getStringExtra(DownloadService.EXTRA_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        if (token != SettingsRepositoryImpl().intentToken) return null
        val uriString = intent.getStringExtra(DownloadService.EXTRA_MODULE_URI)
            ?: return null
        val uri = uriString.toUri()
        return PendingAction.InstallModule(
            uri = uri,
            displayName = getDisplayName(uri),
            requiresConfirmation = false,
        )
    }

    // File manager: open ZIP
    val viewUri = intent.data
    if (viewUri != null && viewUri.scheme == "content" && intent.type == "application/zip") {
        return PendingAction.InstallModule(
            uri = viewUri,
            displayName = getDisplayName(viewUri),
            requiresConfirmation = true,
        )
    }

    // Check deep links
    return when (val deepLink = parseValidatedDeepLink(intent.data)) {
        is KsuDeepLink.Action -> PendingAction.ExecuteAction(deepLink.moduleId)
        is KsuDeepLink.WebUi -> PendingAction.OpenWebUI(deepLink.moduleId)
        null -> null
    }
}

private fun parseValidatedDeepLink(uri: Uri?): KsuDeepLink? {
    if (uri?.scheme != SCHEME_KSU) return null

    val moduleId = uri.getQueryParameter(PARAM_ID)?.takeIf { it.isNotBlank() } ?: return null
    val token = uri.getQueryParameter(PARAM_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
    if (token != SettingsRepositoryImpl().intentToken) return null

    return when (uri.host) {
        HOST_ACTION -> KsuDeepLink.Action(moduleId)
        HOST_WEBUI -> KsuDeepLink.WebUi(moduleId)
        else -> null
    }
}

@SuppressLint("StringFormatInvalid")
@Composable
fun IntentDispatcher(intentChannel: ReceiveChannel<Intent>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalNavigator.current
    // ⚠️ 2026-09-19 修(G3):这两个都是 JNI 调用,在组合期执行,失败会抛 → 闪退。
    //    安全默认值:isSafeMode 失败当"不是安全模式"(不误拦安装流程);
    //    isManager 失败当 true(功能全开,和 Natives.isFullFeatured 的失败默认值保持一致;
    //    真拿不到 root 时后面的 FlashScreen 只会报失败,不会崩)。
    val isSafeMode = runCatching { Natives.isSafeMode }.getOrDefault(false)
    val isManager = runCatching { Natives.isManager }.getOrDefault(true)
    var pendingZipInstall by rememberSaveable(stateSaver = PendingAction.InstallModule.InstallModuleSaver) { mutableStateOf(null) }

    val installDialog = rememberConfirmDialog(
        onConfirm = {
            pendingZipInstall?.let { action ->
                navigator.push(Route.Flash(FlashIt.FlashModules(listOf(action.uri))))
            }
            pendingZipInstall = null
        },
        onDismiss = { pendingZipInstall = null }
    )

    // 【2026-09-16 已按用户要求删除】原本这里有一段「隐身救援」:
    //   隐身(isManager == false)时,任何 App 或浏览器打开 `ksu://` 链接都会弹一个
    //   「关闭隐身模式?」的确认框,确认后关掉隐身。
    //   删除理由:该入口对外暴露了"这台机器装了隐身软件"这件事,而且任何 App 都能拉起它。
    //   ⚠️ 删掉之后的后果:隐身退出的路只剩 (1) 拨号密令 (2) 有 root 时
    //      `su -c 'rm /data/adb/sevenk/stealth'` 再重启。
    //   同时清单里 ksu://action 的 BROWSABLE 分类也已移除 —— 浏览器/其它 App 打不开它了。
    //   (模块快捷方式不受影响:它们用的是显式 Intent(Intent(context, MainActivity::class.java)),
    //    不经过清单过滤器。)
    CollectIntentChannel(intentChannel) { intent ->
        if (!isManager) return@CollectIntentChannel
        val action = resolveIntent(intent) ?: return@CollectIntentChannel

        when (action) {
            is PendingAction.InstallModule -> {
                if (isSafeMode) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.safe_mode_module_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@CollectIntentChannel
                }
                if (action.requiresConfirmation) {
                    pendingZipInstall = action
                    installDialog.showConfirm(
                        title = resources.getString(R.string.module),
                        content = resources.getString(
                            R.string.module_install_prompt_with_name,
                            "\n${action.displayName}"
                        )
                    )
                } else {
                    navigator.push(Route.Flash(FlashIt.FlashModules(listOf(action.uri))))
                }
            }

            is PendingAction.ExecuteAction -> {
                navigator.push(Route.ExecuteModuleAction(action.moduleId, fromShortcut = true))
            }

            is PendingAction.OpenWebUI -> {
                val webIntent = Intent(context, WebUIActivity::class.java)
                    .setData(buildInternalWebUiUri(action.moduleId))
                context.startActivity(webIntent)
            }
        }
    }
}

/**
 * Receive intents inside a [LaunchedEffect] tied to the channel identity.
 * Each emitted intent is processed exactly once; no activity.intent mutation needed.
 */
@Composable
private fun CollectIntentChannel(intentChannel: ReceiveChannel<Intent>, onIntent: suspend (Intent) -> Unit) {
    LaunchedEffect(intentChannel) {
        for (intent in intentChannel) {
            onIntent(intent)
        }
    }
}
