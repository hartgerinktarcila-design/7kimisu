package com.sevenk.core.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sevenk.core.R
import com.sevenk.core.ui.component.ConfirmDialog
import com.sevenk.core.ui.navigation.LocalNavigator
import com.sevenk.core.ui.navigation.Route
import com.sevenk.core.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    bottomPadding: Dp = 0.dp,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    val scrollBehavior = MiuixScrollBehavior()

    // 「内核还没认主」的说明弹窗
    var showNotRecognizedDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val notRecognizedTitle = stringResource(R.string.home_manager_not_recognized_title)
    val notRecognizedDetail = stringResource(R.string.home_manager_not_recognized_detail)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(
                        state = uiState,
                        onInstallClick = { navigator.push(Route.Flash()) },
                        onNotRecognizedClick = { showNotRecognizedDialog = true },
                    )
                    // 「安装 / 更新内核」入口卡
                    if (uiState.lkmMode == true ||
                        (uiState.managerNotRecognized && uiState.isRootAvailable)
                    ) {
                        InstallKernelCard(onClick = { navigator.push(Route.Flash()) })
                    }
                    InfoCard(uiState)
                    Spacer(Modifier.height(bottomPadding))
                }
            }
        }
    }

    ConfirmDialog(
        show = showNotRecognizedDialog,
        title = notRecognizedTitle,
        content = notRecognizedDetail,
        confirm = stringResource(R.string.ok),
        dismiss = stringResource(R.string.cancel),
        onConfirm = { },
        onDismiss = { showNotRecognizedDialog = false },
    )
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    onInstallClick: () -> Unit,
    onNotRecognizedClick: () -> Unit,
) {
    when {
        state.ksuVersion != null -> {
            val workingState = buildString {
                if (state.isSafeMode) append(" [${stringResource(R.string.safe_mode)}]")
                if (state.isLateLoadMode) append(" [${stringResource(R.string.jailbreak_mode)}]")
            }
            val workingMode = when (state.lkmMode) {
                null -> null
                true -> "LKM"
                else -> "GKI"
            }
            Card(
                colors = CardDefaults.defaultColors(
                    color = when {
                        isDynamicColor -> colorScheme.secondaryContainer
                        isInDarkTheme() -> Color(0xFF1A3825)
                        else -> Color(0xFFDFFAE4)
                    }
                ),
                onClick = onInstallClick,
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Tilt,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Icon(
                            modifier = Modifier
                                .padding(end = 20.dp, bottom = 12.dp)
                                .size(80.dp),
                            imageVector = Icons.Rounded.CheckCircleOutline,
                            tint = if (isDynamicColor) {
                                colorScheme.primary.copy(alpha = 0.8f)
                            } else {
                                Color(0xFF36D167)
                            },
                            contentDescription = null,
                        )
                    }
                    Column(modifier = Modifier.padding(16.dp, 14.dp)) {
                        Text(
                            text = stringResource(R.string.home_working) + workingState,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.home_working_version,
                                "${state.ksuVersion}-${state.kernelUAPIVersion}"
                            ),
                            fontSize = 15.sp,
                        )
                        if (workingMode != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = workingMode,
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        state.kernelVersion.isGKI() -> {
            Card(
                onClick = {
                    when {
                        // 有 root → 直达刷写页
                        state.managerNotRecognized && state.isRootAvailable -> onInstallClick()
                        // 没 root → 装不了，把道理讲清楚
                        state.managerNotRecognized -> onNotRecognizedClick()
                        else -> onInstallClick()
                    }
                },
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Tilt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicComponent(
                    title = if (state.managerNotRecognized) {
                        if (state.isRootAvailable) stringResource(R.string.home_can_install)
                        else stringResource(R.string.home_manager_not_recognized_title)
                    } else {
                        stringResource(R.string.home_not_installed)
                    },
                    summary = if (state.managerNotRecognized) {
                        if (state.isRootAvailable) stringResource(R.string.home_can_install_summary)
                        else stringResource(R.string.home_manager_not_recognized)
                    } else {
                        stringResource(R.string.home_click_to_install)
                    },
                    startAction = {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                )
            }
        }

        else -> {
            Card(
                onClick = onInstallClick,
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Tilt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicComponent(
                    title = stringResource(R.string.home_unsupported),
                    summary = stringResource(R.string.home_unsupported_reason),
                    startAction = {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 16.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun InstallKernelCard(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.home_install_kernel),
            summary = stringResource(R.string.home_install_kernel_summary),
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun InfoCard(state: HomeUiState) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = content,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    val systemInfo = state.systemInfo
    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccompDisplay = when (systemInfo.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }
    // 隐身状态只在「未被归一化」时展示（隐身开着时首页整页都是伪装，
    // 不会走到这里 —— ksuVersion 为 null 时 managerNotRecognized 分支接管）。
    val stealthDisplay = when (state.stealthState) {
        1 -> stringResource(R.string.home_stealth_on)
        0 -> stringResource(R.string.home_stealth_off)
        else -> stringResource(R.string.home_stealth_unknown)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoText(
                icon = Icons.Rounded.Tag,
                title = stringResource(R.string.home_manager_version),
                content = systemInfo.managerVersion,
            )
            InfoText(
                icon = Icons.Rounded.DeveloperBoard,
                title = stringResource(R.string.home_kernel),
                content = systemInfo.kernelVersion,
            )
            InfoText(
                icon = Icons.Rounded.Smartphone,
                title = stringResource(R.string.home_device_model),
                content = systemInfo.deviceModel,
            )
            InfoText(
                icon = Icons.Rounded.Fingerprint,
                title = stringResource(R.string.home_fingerprint),
                content = systemInfo.fingerprint,
                bottomPadding = 0.dp,
            )
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoText(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.home_selinux_status),
                content = selinuxDisplay,
            )
            InfoText(
                icon = Icons.Rounded.FilterList,
                title = stringResource(R.string.home_seccomp_status),
                content = seccompDisplay,
            )
            InfoText(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.home_stealth_status),
                content = stealthDisplay,
                bottomPadding = 0.dp,
            )
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoText(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.home_uapi_version),
                content = stringResource(
                    R.string.home_uapi_version_value,
                    state.kernelUAPIVersion?.toString() ?: "-",
                    state.managerUAPIVersion,
                ),
                bottomPadding = 0.dp,
            )
        }
    }
}
