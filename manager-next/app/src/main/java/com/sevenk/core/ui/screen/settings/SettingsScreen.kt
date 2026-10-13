package com.sevenk.core.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sevenk.core.AppExecutors
import com.sevenk.core.R
import com.sevenk.core.security.Stealth
import com.sevenk.core.security.StealthCodeStore
import com.sevenk.core.security.restartUiFresh
import com.sevenk.core.ui.component.ConfirmDialog
import com.sevenk.core.ui.component.InputDialog
import com.sevenk.core.ui.component.KsuIsValid
import com.sevenk.core.ui.navigation.LocalNavigator
import com.sevenk.core.ui.navigation.Route
import com.sevenk.core.ui.theme.ColorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreen(
    bottomPadding: Dp = 0.dp,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    var showStealthConfirm by remember { mutableStateOf(false) }
    var showCodeDialog by remember { mutableStateOf(false) }

    // 密令可能在 /data/adb/sevenk/stealth_code 里(跨卸载备份)—— 优先显示真实生效的值
    LaunchedEffect(Unit) {
        val real = withContext(Dispatchers.IO) {
            StealthCodeStore.sync()
            StealthCodeStore.effectiveCode(context)
        }
        if (real.isNotBlank() && real != uiState.stealthCode) {
            viewModel.setStealthCode(real)
        }
    }

    val stealthToggleFailedHint = stringResource(R.string.settings_stealth_toggle_failed)
    // onClick/onCheckedChange 的 lambda 里不能用 LocalContext 读资源；
    // 带格式参数的文案用 LocalResources（配置感知，lint 认可）。
    val resources = LocalResources.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings),
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
                // ── 外观 ──
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    val themeItems = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_theme),
                        summary = stringResource(R.string.settings_theme_summary),
                        items = themeItems,
                        startAction = {
                            Icon(
                                Icons.Rounded.Brightness4,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        },
                        selectedIndex = ColorMode.fromValue(uiState.colorMode).ordinal,
                        onSelectedIndexChange = { index ->
                            viewModel.setColorMode(ColorMode.entries[index].value)
                        },
                    )
                }

                // ── 隐身 ──
                KsuIsValid {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_stealth),
                            summary = stringResource(R.string.settings_stealth_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.VisibilityOff,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { showStealthConfirm = true },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_stealth_code),
                            summary = "*#*#${uiState.stealthCode}#*#*",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Dialpad,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { showCodeDialog = true },
                        )
                    }
                }

                // ── 内核功能开关 ──
                KsuIsValid {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        FeatureSwitch(
                            title = stringResource(R.string.settings_sulog),
                            summaryOn = stringResource(R.string.settings_sulog_summary),
                            feature = uiState.sulog,
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Article,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onCheckedChange = { enabled ->
                                viewModel.setSulogEnabled(enabled) { ok ->
                                    if (!ok) toast(context, stealthToggleFailedHint)
                                }
                            },
                        )
                        FeatureSwitch(
                            title = stringResource(R.string.settings_adb_root),
                            summaryOn = stringResource(R.string.settings_adb_root_summary),
                            feature = uiState.adbRoot,
                            icon = {
                                Icon(
                                    Icons.Rounded.Adb,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onCheckedChange = { enabled ->
                                viewModel.setAdbRootEnabled(enabled) { ok ->
                                    if (!ok) toast(context, stealthToggleFailedHint)
                                }
                            },
                        )
                        FeatureSwitch(
                            title = stringResource(R.string.settings_selinux_hide),
                            summaryOn = stringResource(R.string.settings_selinux_hide_summary),
                            feature = uiState.selinuxHide,
                            icon = {
                                Icon(
                                    Icons.Rounded.Security,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onCheckedChange = { enabled ->
                                viewModel.setSelinuxHideEnabled(enabled) { status ->
                                    when (status) {
                                        0 -> Unit
                                        -android.system.OsConstants.EAGAIN ->
                                            toast(context, resources.getString(R.string.settings_selinux_hide_reboot_required))
                                        else ->
                                            toast(context, resources.getString(R.string.settings_selinux_hide_failed, status))
                                    }
                                }
                            },
                        )
                        FeatureSwitch(
                            title = stringResource(R.string.settings_shell_su_adb_only),
                            summaryOn = stringResource(R.string.settings_shell_su_adb_only_summary),
                            feature = uiState.shellSuAdbOnly,
                            icon = {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onCheckedChange = { enabled ->
                                viewModel.setShellSuAdbOnlyEnabled(enabled) { ok ->
                                    if (!ok) toast(context, stealthToggleFailedHint)
                                }
                            },
                        )
                    }
                }

                // ── 关于 ──
                Card(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.about),
                        summary = stringResource(R.string.about_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.Info,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        },
                        onClick = { navigator.push(Route.About) },
                    )
                }
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }

    // 开启隐身确认框
    ConfirmDialog(
        show = showStealthConfirm,
        title = stringResource(R.string.settings_stealth_confirm_title),
        content = stringResource(R.string.settings_stealth_confirm_content, uiState.stealthCode),
        confirm = stringResource(R.string.settings_stealth_confirm_yes),
        dismiss = stringResource(R.string.cancel),
        onConfirm = {
            // 开关失败(内核太旧 / 没被认主)必须说出来，且不重建界面（免得装成"成功"）
            val err = Stealth.setEnabledReporting(true)
            if (err != null) {
                toast(context, err)
            } else {
                Stealth.ensureLauncherVisible(context)
                restartUiFresh(context)
            }
        },
        onDismiss = { showStealthConfirm = false },
    )

    // 密令修改框
    InputDialog(
        show = showCodeDialog,
        title = stringResource(R.string.settings_stealth_code),
        initialValue = uiState.stealthCode,
        confirm = stringResource(R.string.ok),
        dismiss = stringResource(R.string.cancel),
        digitsOnly = true,
        onConfirm = { raw ->
            val code = raw.filter { it.isDigit() }.take(12)
            when {
                code.length < 4 ->
                    toast(context, resources.getString(R.string.settings_stealth_code_too_short))
                code != uiState.stealthCode -> {
                    viewModel.setStealthCode(code)
                    // 再写一份到 /data/adb:卸载重装后密令依然有效(否则会锁死)
                    AppExecutors.io.execute { StealthCodeStore.write(code) }
                }
            }
        },
        onDismiss = { showCodeDialog = false },
    )
}

@Composable
private fun FeatureSwitch(
    title: String,
    summaryOn: String,
    feature: FeatureUiState,
    icon: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val summary = when (feature.status) {
        "unsupported" -> stringResource(R.string.feature_status_unsupported_summary)
        "unknown" -> stringResource(R.string.feature_status_unreadable_summary)
        "managed" -> stringResource(R.string.feature_status_managed_summary)
        else -> summaryOn
    }
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = icon,
        enabled = feature.status == "supported" || feature.status == "managed",
        checked = feature.enabled,
        onCheckedChange = onCheckedChange,
    )
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
