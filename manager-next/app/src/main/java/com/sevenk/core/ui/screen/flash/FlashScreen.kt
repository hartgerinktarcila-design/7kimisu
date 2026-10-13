package com.sevenk.core.ui.screen.flash

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.R
import com.sevenk.core.getKernelVersion
import com.sevenk.core.security.Stealth
import com.sevenk.core.ui.navigation.LocalNavigator
import com.sevenk.core.ui.navigation.Route
import com.sevenk.core.ui.util.FlashResult
import com.sevenk.core.ui.util.flashModule
import com.sevenk.core.ui.util.getFileName
import com.sevenk.core.ui.util.getPersistedStealthFlag
import com.sevenk.core.ui.util.installBoot
import com.sevenk.core.ui.util.isAbDevice
import com.sevenk.core.ui.util.reboot
import com.sevenk.core.ui.util.rootAvailable
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 刷写状态 */
enum class FlashingStatus { IDLE, FLASHING, SUCCESS, FAILED }

/**
 * boot 修补页（[Route.Flash]）。
 * bootUri 为 null 时先显示入口（选文件 / 直接安装 / 装到未使用槽位 + 高级开关），
 * 选定后在本页跑 ksud boot-patch 并滚动显示日志。
 */
@Composable
fun FlashScreen(flash: Route.Flash) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var bootUri by rememberSaveable { mutableStateOf(flash.bootUri) }
    var ota by rememberSaveable { mutableStateOf(flash.ota) }
    var allowShell by rememberSaveable { mutableStateOf(flash.allowShell) }
    var enableAdb by rememberSaveable { mutableStateOf(flash.enableAdb) }
    var backup by rememberSaveable { mutableStateOf(flash.backup) }

    var logText by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(FlashingStatus.IDLE.name) }
    var showReboot by rememberSaveable { mutableStateOf(false) }
    val flashingStatus = FlashingStatus.valueOf(status)
    val flashing = flashingStatus == FlashingStatus.FLASHING

    // 环境探测（IO 线程）
    val rootOk by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { runCatching { rootAvailable() }.getOrDefault(false) }
    }
    val isGkiDevice by produceState(initialValue = false) {
        value = runCatching { getKernelVersion().isGKI() }.getOrDefault(false)
    }
    val abDevice by produceState(initialValue = false) {
        value = runCatching { isAbDevice() }.getOrDefault(false)
    }
    // 隐身时藏起「直接安装」这两个 root 特征入口（三态：读不到再看盘上标志）
    val stealthKernelState = Stealth.state()
    val persistedStealthFlag by produceState<Boolean?>(initialValue = null, stealthKernelState) {
        value = if (stealthKernelState == Stealth.State.UNKNOWN) getPersistedStealthFlag() else null
    }
    val stealthHides = when (stealthKernelState) {
        Stealth.State.ON -> true
        Stealth.State.OFF -> false
        Stealth.State.UNKNOWN -> persistedStealthFlag == true
    }

    val selectImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri -> bootUri = uri.toString() }
    }

    // 刷写中拦截返回键（手势与实体键都拦）
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = flashing,
        onBackCompleted = {},
    )

    fun startFlash(targetUri: String?, targetOta: Boolean) {
        if (flashing) return
        status = FlashingStatus.FLASHING.name
        logText = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installBoot(
                        bootUri = targetUri?.let { Uri.parse(it) },
                        ota = targetOta,
                        partition = null,
                        allowShell = allowShell,
                        enableAdb = enableAdb,
                        forceBackup = backup,
                        onStdout = { line -> logText += "$line\n" },
                        onStderr = { line -> logText += "$line\n" },
                    )
                }.getOrElse {
                    FlashResult(-1, it.message ?: "flash failed", false)
                }
            }
            showReboot = result.showReboot
            status = if (result.code == 0) FlashingStatus.SUCCESS.name else FlashingStatus.FAILED.name
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.flash_title),
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            // ── 入口区（刷写完成后仍可见，方便再刷一次） ──
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.flash_select_file),
                    summary = bootUri?.let { Uri.parse(it).getFileName(context) }
                        ?: stringResource(R.string.flash_select_file_summary),
                    startAction = {
                        Icon(
                            Icons.Rounded.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                    enabled = !flashing,
                    onClick = {
                        selectImageLauncher.launch(
                            Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "application/octet-stream"
                            }
                        )
                    },
                )
                if (rootOk && isGkiDevice && !stealthHides) {
                    BasicComponent(
                        title = stringResource(R.string.flash_direct_install),
                        summary = stringResource(R.string.flash_direct_install_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.Build,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = colorScheme.onBackground,
                            )
                        },
                        enabled = !flashing,
                        onClick = { startFlash(null, false) },
                    )
                    if (abDevice) {
                        BasicComponent(
                            title = stringResource(R.string.flash_install_inactive_slot),
                            summary = stringResource(R.string.flash_install_inactive_slot_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Build,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = colorScheme.onBackground,
                                )
                            },
                            enabled = !flashing,
                            onClick = { ota = true; startFlash(null, true) },
                        )
                    }
                }
            }

            // ── 高级选项 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.flash_allow_shell),
                    summary = stringResource(R.string.flash_allow_shell_summary),
                    checked = allowShell,
                    onCheckedChange = { allowShell = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.flash_enable_adb),
                    summary = stringResource(R.string.flash_enable_adb_summary),
                    checked = enableAdb,
                    onCheckedChange = { enableAdb = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.flash_force_backup),
                    summary = stringResource(R.string.flash_force_backup_summary),
                    checked = backup,
                    onCheckedChange = { backup = it },
                )
            }

            Button(
                onClick = { startFlash(bootUri, ota) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !flashing && bootUri != null,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.flash_start))
            }

            // ── 日志区 ──
            if (logText.isNotEmpty() || flashing) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = logText.ifEmpty { "..." },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (flashingStatus == FlashingStatus.FAILED) {
                Text(
                    text = stringResource(R.string.flash_failed),
                    color = colorScheme.onError,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (showReboot) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                runCatching { reboot() }
                                    .onFailure {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                R.string.reboot_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(stringResource(R.string.reboot))
                    }
                }
            }

            // 完成或放弃后回上一页
            if (!flashing && (flashingStatus == FlashingStatus.SUCCESS || flashingStatus == FlashingStatus.FAILED)) {
                Button(
                    onClick = { navigator.pop() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.back))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 模块 zip 安装页（[Route.FlashModule]）：进来自动开始装，日志滚动显示。
 */
@Composable
fun FlashScreen(moduleZip: Route.FlashModule) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    var logText by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(FlashingStatus.IDLE.name) }
    val flashingStatus = FlashingStatus.valueOf(status)
    val flashing = flashingStatus == FlashingStatus.FLASHING
    // 失败文案在组合期取好（LaunchedEffect 的 lambda 里不能用 LocalContext 读资源）
    val installFailedText = stringResource(R.string.module_install_failed)

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = flashing,
        onBackCompleted = {},
    )

    LaunchedEffect(moduleZip.zipUri) {
        if (flashingStatus != FlashingStatus.IDLE) return@LaunchedEffect
        status = FlashingStatus.FLASHING.name
        val result = withContext(Dispatchers.IO) {
            runCatching {
                flashModule(
                    uri = Uri.parse(moduleZip.zipUri),
                    onStdout = { line -> logText += "$line\n" },
                    onStderr = { line -> logText += "$line\n" },
                )
            }.getOrElse {
                FlashResult(-1, it.message ?: "install failed", false)
            }
        }
        status = if (result.code == 0) FlashingStatus.SUCCESS.name else FlashingStatus.FAILED.name
        if (result.code != 0) {
            Toast.makeText(context, installFailedText, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.module_install),
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = logText.ifEmpty { "..." },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (flashingStatus == FlashingStatus.SUCCESS) {
                Text(
                    text = stringResource(R.string.reboot_to_apply),
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (!flashing) {
                Button(
                    onClick = { navigator.pop() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.back))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
