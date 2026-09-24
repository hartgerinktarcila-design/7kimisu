package com.sevenk.core.ui.screen.install

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.sevenk.core.R
import com.sevenk.core.getKernelVersion
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.component.choosekmidialog.ChooseKmiDialog
import com.sevenk.core.ui.component.dialog.DownloadDialog
import com.sevenk.core.ui.component.dialog.rememberLoadingDialog
import com.sevenk.core.ui.navigation3.LocalNavigator
import com.sevenk.core.ui.navigation3.Route
import com.sevenk.core.ui.screen.flash.FlashIt
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.util.LkmSelection
import com.sevenk.core.ui.util.getAvailablePartitions
import com.sevenk.core.ui.util.getCurrentKmi
import com.sevenk.core.ui.util.getDefaultPartition
import com.sevenk.core.ui.util.getPersistedStealthFlag
import com.sevenk.core.ui.util.getSlotSuffix
import com.sevenk.core.ui.util.isAbDevice
import com.sevenk.core.ui.util.probeRemoteBootPartitions
import com.sevenk.core.ui.util.rootAvailable as probeRootAvailable
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState

@Composable
fun InstallScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val miuixSnackbarHost = remember { MiuixSnackbarHostState() }
    val uiMode = LocalUiMode.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var probeJob by remember { mutableStateOf<Job?>(null) }
    val loadingDialog = rememberLoadingDialog()

    var installMethod by rememberSaveable { mutableStateOf<InstallMethod?>(null) }
    var downloadDialogShown by rememberSaveable { mutableStateOf(false) }
    var remotePartitions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var remotePartitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var lkmSelection by rememberSaveable { mutableStateOf<LkmSelection>(LkmSelection.KmiNone) }
    var partitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var hasCustomSelected by rememberSaveable { mutableStateOf(false) }
    val showChooseKmiDialog = rememberSaveable { mutableStateOf(false) }
    var advancedOptionsShown by rememberSaveable { mutableStateOf(false) }
    var allowShell by rememberSaveable { mutableStateOf(false) }
    var enableAdb by rememberSaveable { mutableStateOf(false) }
    var forceBackup by rememberSaveable { mutableStateOf(false) }

    val currentKmi by produceState(initialValue = "") { value = getCurrentKmi() }
    val partitions by produceState(initialValue = emptyList()) { value = getAvailablePartitions() }
    val defaultPartition by produceState(initialValue = "") { value = getDefaultPartition() }
    // ⚠️ 导入时用了别名 `probeRootAvailable`（`rootAvailable as probeRootAvailable`）：
    //    否则下面那个同名局部变量会把这个顶层函数**遮住**（Kotlin 里局部 val 优先）。
    val rootAvailable by produceState(initialValue = false) { value = probeRootAvailable() }
    val isAbDevice by produceState(initialValue = false) { value = isAbDevice() }
    val isGkiDevice by produceState(initialValue = false) { value = getKernelVersion().isGKI() }

    val selectFileTip = stringResource(id = R.string.select_file_tip, defaultPartition)
    val selectFileTipNoGki = stringResource(id = R.string.select_file_tip_nogki)
    val downloadFileMsg = stringResource(id = R.string.download_dialog_msg)

    // ⚠️ 隐身模式:界面伪装成「未安装」,所以这一页也必须装成**"真·没 root 的设备"**的样子。
    //
    // 真·没 root 的设备上,KernelSU 的安装页本来就不会列出「直接安装(推荐)」和
    // 「安装到未使用槽位」—— 那两项都要 root 才做得成,所以上游就是用 `rootAvailable` 卡住的。
    // 隐身开着时本 App 其实仍有 root,所以还必须**再叠一层"隐身就不显示"**,
    // 否则这两项会露出来 = 隐身当场穿帮。
    //
    // 判据就两条(其余自创的归属/镜像五态判定已按用户要求删掉,退回上游形态):
    //   1. **上游原判据**:`rootAvailable && isGkiDevice`(「安装到未使用槽位」再加 `isAbDevice`);
    //   2. **隐身**:确定在隐身 → 藏。
    //      内核明确读到 ON → 藏;明确读到 OFF → 显示;
    //      读不出来(UNKNOWN,典型是重装/换 appid/待重启导致 `STEALTH_GET` 被拒)
    //      → 再看磁盘上那份持久化标志,只有明确写着 '1' 才继续藏
    //      (盘上写着 '0' 或读不到 → 显示;真机上"内核读不出来 + 隐身其实是关的"要靠这条,
    //       判据与依据见 Stealth.kt 的 STEALTH_FLAG_PATH 注释)。
    val stealthKernelState = Stealth.state()
    val persistedStealthFlag by produceState<Boolean?>(initialValue = null, stealthKernelState) {
        value = if (stealthKernelState == Stealth.State.UNKNOWN) getPersistedStealthFlag() else null
    }
    val stealthHides = when (stealthKernelState) {
        Stealth.State.ON -> true
        Stealth.State.OFF -> false
        Stealth.State.UNKNOWN -> persistedStealthFlag == true
    }
    val installMethodOptions = remember(rootAvailable, stealthHides, isAbDevice, isGkiDevice, selectFileTip, selectFileTipNoGki, downloadFileMsg) {
        buildList {
            add(InstallMethod.SelectFile(summary = if (isGkiDevice) selectFileTip else selectFileTipNoGki))
            add(InstallMethod.DownloadFile(summary = downloadFileMsg))
            if (rootAvailable && isGkiDevice && !stealthHides) {
                add(InstallMethod.DirectInstall)
                if (isAbDevice) add(InstallMethod.DirectInstallToInactiveSlot)
            }
        }
    }

    val isOta = installMethod is InstallMethod.DirectInstallToInactiveSlot
    val slotSuffix by produceState(initialValue = "", isOta) { value = getSlotSuffix(isOta) }
    val defaultIndex = remember(partitions, defaultPartition) {
        partitions.indexOf(defaultPartition).coerceAtLeast(0)
    }

    LaunchedEffect(partitions, defaultIndex, hasCustomSelected) {
        if (partitions.isEmpty()) return@LaunchedEffect
        if (!hasCustomSelected) {
            partitionSelectionIndex = defaultIndex.coerceIn(0, partitions.lastIndex)
        } else if (partitionSelectionIndex > partitions.lastIndex) {
            partitionSelectionIndex = partitions.lastIndex
        }
    }

    val displayPartitions = remember(partitions, defaultPartition) {
        partitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }
    val remoteDisplayPartitions = remember(remotePartitions, defaultPartition) {
        remotePartitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                miuixSnackbarHost.showSnackbar(message)
            }
        }
    }

    val onInstall = {
        installMethod?.let { rawMethod ->
            // 🟡 v2.3（审计 B，纵深防御）：`installMethod` 是 rememberSaveable ——
            // 旋转 / 进程重建后恢复出来的旧值，可能**已经不在**当前 `installMethodOptions` 里
            // （典型：当时有 root 选了「直接安装」，回来时 root 没了 / 隐身被打开了）。
            // 那种情况下**绝不能按旧值直接刷分区**。
            // ⚠️ 也**不要**"降级成选择文件并修补"：`InstallMethod.SelectFile(uri = null)` 在
            // FlashScreen 里等价于 `boot-patch -f`（**就是直接安装**，见 KsuCli.installBoot），
            // 降级反而更危险。所以这里直接拦下，让用户在当前列表里重新选一次。
            if (installMethodOptions.none { it::class == rawMethod::class }) {
                showMessage(resources.getString(R.string.install_method_stale))
                return@let
            }
            navigator.push(
                Route.Flash(
                    when (rawMethod) {
                        is InstallMethod.DownloadFile -> FlashIt.DownloadBoot(
                            url = rawMethod.url ?: return@let,
                            partition = rawMethod.partition ?: return@let,
                            lkm = lkmSelection,
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            backup = forceBackup
                        )
                        else -> FlashIt.FlashBoot(
                            boot = if (rawMethod is InstallMethod.SelectFile) rawMethod.uri else null,
                            lkm = lkmSelection,
                            ota = rawMethod is InstallMethod.DirectInstallToInactiveSlot,
                            partition = partitions.getOrNull(partitionSelectionIndex),
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            backup = rawMethod is InstallMethod.SelectFile && forceBackup
                        )
                    }
                )
            )
        }
    }

    ChooseKmiDialog(
        show = showChooseKmiDialog.value,
        onDismissRequest = { showChooseKmiDialog.value = false },
        onSelected = { kmi ->
            kmi?.let {
                lkmSelection = LkmSelection.KmiString(it)
                onInstall()
            }
        }
    )

    DownloadDialog(
        show = downloadDialogShown,
        onConfirm = { url ->
            downloadDialogShown = false
            probeJob?.cancel()
            probeJob = scope.launch {
                try {
                    loadingDialog.showLoading()
                    val result = probeRemoteBootPartitions(url)
                    if (result.partitions.isEmpty()) {
                        showMessage(resources.getString(R.string.download_no_boot_partition))
                    } else {
                        val defaultIdx = result.partitions.indexOf(defaultPartition).coerceAtLeast(0)
                        remotePartitions = result.partitions
                        remotePartitionSelectionIndex = defaultIdx
                        installMethod = InstallMethod.DownloadFile(
                            url = url,
                            partition = result.partitions[defaultIdx],
                            summary = downloadFileMsg,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showMessage(
                        resources.getString(R.string.download_probe_failed, e.message ?: "")
                    )
                } finally {
                    loadingDialog.hide()
                }
            }
        },
        onDismiss = { downloadDialogShown = false }
    )

    val selectLkmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                if (isKoFile(context, uri)) {
                    lkmSelection = LkmSelection.LkmUri(uri)
                } else {
                    lkmSelection = LkmSelection.KmiNone
                    showMessage(resources.getString(R.string.install_only_support_ko_file))
                }
            }
        }
    }
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.SelectFile(uri, summary = if (isGkiDevice) selectFileTip else selectFileTipNoGki)
            }
        }
    }

    val state = InstallUiState(
        installMethod = installMethod,
        lkmSelection = lkmSelection,
        partitionSelectionIndex = partitionSelectionIndex,
        displayPartitions = displayPartitions,
        remoteDisplayPartitions = remoteDisplayPartitions,
        remotePartitionSelectionIndex = remotePartitionSelectionIndex,
        currentKmi = currentKmi,
        slotSuffix = slotSuffix,
        installMethodOptions = installMethodOptions,
        canSelectPartition = installMethod is InstallMethod.DirectInstall ||
            installMethod is InstallMethod.DirectInstallToInactiveSlot ||
            installMethod is InstallMethod.DownloadFile,
        advancedOptionsShown = advancedOptionsShown,
        allowShell = allowShell,
        enableAdb = enableAdb,
        forceBackup = forceBackup,
        canForceBackup = installMethod is InstallMethod.SelectFile,
    )
    val actions = InstallScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSelectMethod = { method -> installMethod = method },
        onDownloadFile = { downloadDialogShown = true },
        onSelectBootImage = {
            selectImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onUploadLkm = {
            selectLkmLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onClearLkm = { lkmSelection = LkmSelection.KmiNone },
        onSelectPartition = { index ->
            hasCustomSelected = true
            val method = installMethod
            if (method is InstallMethod.DownloadFile) {
                remotePartitionSelectionIndex = index
                installMethod = method.copy(partition = remotePartitions.getOrNull(index))
            } else {
                partitionSelectionIndex = index
            }
        },
        onNext = {
            val isLkmSelected = lkmSelection != LkmSelection.KmiNone
            val isKmiUnknown = currentKmi.isBlank()
            val isKmiUnresolved = when (installMethod) {
                // The download flow extracts the KMI itself; no manual
                // selection needed.
                is InstallMethod.DownloadFile -> false
                is InstallMethod.SelectFile -> true
                else -> isKmiUnknown
            }
            if (!isLkmSelected && isKmiUnresolved) {
                showChooseKmiDialog.value = true
            } else {
                onInstall()
            }
        },
        onAdvancedOptionsClicked = {
            advancedOptionsShown = !advancedOptionsShown
        },
        onSelectAllowShell = {
            allowShell = it
        },
        onSelectEnableAdb = {
            enableAdb = it
        },
        onSelectForceBackup = {
            forceBackup = it
        }
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> InstallScreenMiuix(state, actions, miuixSnackbarHost)
        UiMode.Material -> InstallScreenMaterial(state, actions, snackbarHost)
    }
}
