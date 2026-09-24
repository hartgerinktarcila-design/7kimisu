package com.sevenk.core.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sevenk.core.KernelVersion
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.component.WarningLevel
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.dialog.rememberInfoDialog
import com.sevenk.core.ui.component.material.ExpressiveScaffold
import com.sevenk.core.ui.component.material.SegmentedColumn
import com.sevenk.core.ui.component.material.SegmentedListItem
import com.sevenk.core.ui.component.material.WarningCard
import com.sevenk.core.ui.component.material.expressiveTopAppBarColors
import com.sevenk.core.ui.component.rebootlistpopup.RebootListPopup
import com.sevenk.core.ui.component.statustag.StatusTag
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.util.LegacyDataMigration
import com.sevenk.core.ui.util.rememberMigrationRestartPending

@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // 「旧版 7kimisu」提示卡要用它拉起系统卸载界面(v2.2)
    val context = androidx.compose.ui.platform.LocalContext.current
    // ⚠️ v0.13.157:这里原来有个 `stealthOn`(隐身时藏「关于 7kimisu」/「支持开发·了解 KernelSU」
    //    两张卡),**已删除**。用户判据:这两张卡是"一个**没有 root 的用户**点进来"就会看到的
    //    东西,隐身必须照常显示 —— 隐身只改"显示什么内容"(比如安装页里需要 root 的两个方法),
    //    不该把没 root 的人也看得到的卡片藏掉。
    //    (「伪装成内核未就绪」仍然由 HomeViewModel 把 UiState 归一化完成,
    //     见 HomeUiState.asStealthKernelNotReady —— 那是内容侧,不是外观侧。)

    ExpressiveScaffold(
        topBar = { TopBar(scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (state.checkUpdateEnabled) {
                UpdateCard(state = state, actions = actions)
            }
            if (state.showManagerPrBuildWarning) {
                WarningCard(stringResource(id = R.string.home_pr_build_warning), level = WarningLevel.Notice)
            } else if (state.showKernelPrBuildWarning) {
                WarningCard(stringResource(id = R.string.home_pr_kernel_warning), level = WarningLevel.Notice)
            }
            if (state.showGkiWarning) {
                WarningCard(stringResource(id = R.string.home_gki_warning), level = WarningLevel.Notice)
            }
            if (state.requiresNewKernel) {
                WarningCard(
                    stringResource(
                        id = if (state.lkmMode == true) R.string.require_kernel_version else R.string.require_kernel_version_gki
                    ),
                    // 隐身下本来就不会出现(requiresNewKernel = isManager && …)
                    onClick = if (state.lkmMode == true) actions.onInstallClick else null
                )
            }
            if (state.requiresNewManager) {
                WarningCard(
                    stringResource(
                        id = R.string.require_manager_version
                    )
                )
            }
            if (state.showLkmUpdate) {
                WarningCard(
                    message = stringResource(R.string.home_lkm_update_available),
                    level = WarningLevel.Notice,
                    onClick = actions.onInstallClick,
                )
            }
            if (state.showRootWarning) {
                WarningCard(stringResource(id = R.string.grant_root_failed))
            }
            // 🔴 D3(v2.2)：数据目录刚迁移过 → 提示"请重启一次设备"。
            // 与 Miuix 版语义完全一致，见 HomeMiuix.kt 同名段落。
            if (rememberMigrationRestartPending()) {
                WarningCard(
                    message = stringResource(id = R.string.migration_restart_notice),
                    onClick = { LegacyDataMigration.acknowledgeRestartNotice(context) },
                )
            }
            StatusCard(
                state = state,
                actions = actions,
            )
            InfoCard(systemInfo = state.systemInfo)
            // ⚠️ v0.13.157:这两张卡**隐身时照常显示**(原来外面套着 `if (!stealthOn)`,已撤)。
            //    它们明写「7kimisu / 第三方 KernelSU 分支」和
            //    「KernelSU is, and always will be, free and open source」——
            //    但这正是"一个没 root 的用户"点进来会看到的东西,所以不该藏
            //    (开屏公告同理,见 AnnouncementPopup)。
            About7kCard()
            SupportLinks(onOpenUrl = actions.onOpenUrl)
            Spacer(
                Modifier.height(
                    // ⚠️ Natives.isFullFeatured() 是 JNI 调用,组合期抛异常会闪退;失败当 true(功能全开)
                    bottomInnerPadding + if (!runCatching { Natives.isFullFeatured() }.getOrDefault(true))
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                )
            )
        }
    }
}

@Composable
private fun UpdateCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    val newVersion = state.latestVersionInfo
    val title = stringResource(id = R.string.module_changelog)
    val updateText = stringResource(id = R.string.module_update)

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val updateDialog = rememberConfirmDialog(onConfirm = { actions.onOpenUrl(newVersion.downloadUrl) })
        WarningCard(
            message = stringResource(id = R.string.new_version_available, newVersion.versionCode),
            level = WarningLevel.Notice
        ) {
            if (newVersion.changelog.isEmpty()) {
                actions.onOpenUrl(newVersion.downloadUrl)
            } else {
                updateDialog.showConfirm(
                    title = title,
                    content = newVersion.changelog,
                    markdown = true,
                    confirm = updateText
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = { RebootListPopup() },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ✅ 2026-09-21(v0.13.157):隐身时这张卡**照常可点**,点击行为与正常时完全一致
    //    (点「内核未就绪」弹原来的说明弹窗 —— 那正是"一个没 root 的用户"点进去会看到的东西)。
    //    v0.13.156 曾给隐身加一个只弹「暂时无法处理，请稍后再试」的首选分支,已撤。
    //    另一处保留的隐身差异是右侧的「越狱」按钮不画(它启动 late-load,是 root 管理器特征)。
    //    判定用 Stealth.looksStealthy()(v2.1,审计 P0-2):"不是明确读到 0 就藏",
    //    读失败 / 内核不支持时也藏 —— 免得把 root 特征(越狱按钮)露出来。
    //    不影响「从别的管理器转过来」(那时内核明确说隐身关着)的正常流程。
    val stealthOn = Stealth.looksStealthy()
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        val ksuActive = state.ksuVersion != null
        val notInstalled = !ksuActive && state.kernelVersion.isGKI()

        // 「内核还没认主」的说明弹窗 —— 2026-09-16 修「点了没反应」时加的。
        // 这个状态下管理器拿不到 root(见 kernel/supercall/perm.c 的 allowed_for_su_authed),
        // 所以既不能替用户重启、也不能靠安装解决 —— 只能把道理讲清楚。
        val notRecognizedDialog = rememberInfoDialog()
        val notRecognizedTitle = stringResource(R.string.home_manager_not_recognized_title)
        val notRecognizedDetail = stringResource(R.string.home_manager_not_recognized_detail)
        // ⚠️ v0.13.157:原来这里还有一个 `stealthTapHint`(隐身专用的「暂时无法处理，请稍后再试」),
        //    连它引用的字符串资源 [R.string.home_stealth_tap_hint] 一起删掉了
        //    —— 隐身时点这一行现在和正常时一样,弹的就是上面这段原始说明。

        val containerColor = if (ksuActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
        val contentColor = MaterialTheme.colorScheme.contentColorFor(containerColor)

        val statusIcon = when {
            ksuActive -> Icons.Rounded.CheckCircle
            notInstalled -> Icons.Rounded.Warning
            else -> Icons.Rounded.Block
        }
        // 状态卡文字固定,不再提供自定义
        val customStatusTitle = STATUS_CARD_TEXT
        val statusTitle = when {
            ksuActive -> customStatusTitle
            // 装好了却写「未安装」是误导。有 root 时直接说「可以安装了」——把下一步摆在脸上。
            notInstalled && state.managerNotRecognized ->
                if (state.isRootAvailable) stringResource(R.string.home_can_install)
                else notRecognizedTitle
            notInstalled -> stringResource(R.string.home_not_installed)
            else -> stringResource(R.string.home_unsupported)
        }
        val statusSummary = when {
            ksuActive -> stringResource(R.string.home_working_version, "${state.ksuVersion}-${state.kernelUAPIVersion}")
            // 有 root 但没有我们的内核 → 这是「从别的管理器转过来」的人,该去安装。
            // (本应用能不能拿到 root 是唯一可靠判据,见 kernel/supercall/perm.c)
            notInstalled && state.managerNotRecognized && state.isRootAvailable ->
                stringResource(R.string.home_can_install_summary)
            // 没 root → 装不了,点一下会弹说明(重启 / 先去别的管理器授权)
            notInstalled && state.managerNotRecognized ->
                stringResource(R.string.home_manager_not_recognized)
            notInstalled -> stringResource(R.string.home_click_to_install)
            else -> stringResource(R.string.home_unsupported_reason)
        }
        val workingMode = if (ksuActive) {
            when (state.lkmMode) {
                null -> ""
                true -> "LKM"
                else -> "GKI"
            }
        } else ""

        val statusTrailing: (@Composable () -> Unit)? = if (ksuActive && workingMode.isNotEmpty()) {
            {
                StatusTag(
                    label = workingMode,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    backgroundColor = MaterialTheme.colorScheme.primary
                )
            }
        } else if (!stealthOn && notInstalled && state.isSELinuxPermissive) {
            {
                Button(
                    onClick = actions.onJailbreakClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.home_jailbreak))
                }
            }
        } else null

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    // 2026-09-16 修两轮:
                    //   ① 以前 onClick「什么都不做」,而 combinedClickable 照样消费点击、
                    //      照样画水波纹 → 用户看到有反馈却什么都没发生 = 「点了没反应」。
                    //   ② 后来改成一律弹「重启一次」→ 又把**从别的管理器转过来**的人堵死了。
                    //   现在按「本应用有没有拿到 root」分流(唯一可靠判据)。
                    //
                    // ✅ 这张卡**照常可点** —— v0.13.149 曾把点击整个摘掉,
                    //    但用户指出"点不了"本身就可疑。
                    //
                    // ⚠️ v0.13.157:隐身时**不再是特例**。v0.13.156 曾在这里插一个
                    //    `stealthOn ->` 首选分支,只弹一句「暂时无法处理，请稍后再试」、
                    //    同时堵住安装页入口 —— **已撤**。用户判据:点「内核未就绪」弹出的那段
                    //    原始说明,正是一个**没有 root 的用户**点进来会看到的东西,
                    //    所以隐身时必须弹它(而不是弹一句"什么都不漏"的自造提示)。
                    //    唯一保留的隐身差异:右边的「越狱」按钮不画(见上面的 statusTrailing)。
                    onClick = {
                        when {
                            state.isLateLoadMode -> Unit
                            // 有 root → 直达安装页:授权 → 点一下 → 直接安装 → 重启
                            state.managerNotRecognized && state.isRootAvailable ->
                                actions.onInstallClick()
                            // 没 root → 装不了,把两种情况讲清楚
                            state.managerNotRecognized -> notRecognizedDialog.show(
                                notRecognizedTitle,
                                notRecognizedDetail,
                            )
                            else -> actions.onInstallClick()
                        }
                    },
                ),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
        ) {
            androidx.compose.foundation.layout.Box {
            ListItem(
                modifier = Modifier,
                leadingContent = {
                    Icon(statusIcon, contentDescription = statusTitle)
                },
                trailingContent = statusTrailing,
                overlineContent = null,
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusSummary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.showCustomLkmBadge) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(R.string.home_lkm_custom),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor,
                    leadingContentColor = contentColor,
                    trailingContentColor = contentColor,
                    supportingContentColor = contentColor.copy(alpha = 0.7f)
                ),
                elevation = ListItemDefaults.elevation(),
                content = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (statusTitle.isNotBlank()) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleMediumEmphasized
                            )
                        }
                        if (ksuActive && state.isSafeMode) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(id = R.string.safe_mode),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                backgroundColor = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                        if (ksuActive && state.isLateLoadMode) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(id = R.string.jailbreak_mode),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                backgroundColor = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                    }
                },
            )
            }
        }
    }
}

@Composable
private fun About7kCard(
    modifier: Modifier = Modifier,
) {
    val dialog = rememberConfirmDialog()
    SegmentedColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SegmentedListItem(
                onClick = {
                    dialog.showConfirm(
                        title = "关于 7kimisu",
                        content = ABOUT_7K_TEXT,
                        markdown = true,
                        confirm = "知道了",
                    )
                },
                headlineContent = { Text("关于 7kimisu") },
                supportingContent = { Text("第三方 KernelSU 分支 · 点这里看说明") },
                leadingContent = { Icon(Icons.Rounded.Info, "关于 7kimisu") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
    }
}

@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val learnMoreUrl = stringResource(R.string.home_learn_kernelsu_url)

    SegmentedColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SegmentedListItem(
                onClick = { onOpenUrl("https://patreon.com/weishu") },
                headlineContent = { Text(stringResource(R.string.home_support_title)) },
                supportingContent = { Text(stringResource(R.string.home_support_content)) },
                leadingContent = {
                    Icon(Icons.Filled.VolunteerActivism, stringResource(R.string.home_support_title))
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
        item {
            SegmentedListItem(
                onClick = { onOpenUrl(learnMoreUrl) },
                headlineContent = { Text(stringResource(R.string.home_learn_kernelsu)) },
                supportingContent = { Text(stringResource(R.string.home_click_to_learn_kernelsu)) },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.home_learn_kernelsu))
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
    }
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun InfoCardItem(
        icon: ImageVector,
        label: String,
        content: String,
        modifier: Modifier = Modifier,
    ) {
        SegmentedListItem(
            modifier = modifier,
            headlineContent = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(imageVector = icon, contentDescription = label) },
            supportingContent = {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Tag,
                    label = stringResource(R.string.home_manager_version),
                    content = systemInfo.managerVersion,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.DeveloperBoard,
                    label = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                    modifier = Modifier,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Smartphone,
                    label = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Fingerprint,
                    label = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                )
            }
        }
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Security,
                    label = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.FilterList,
                    label = stringResource(R.string.home_seccomp_status),
                    content = seccompDisplay,
                )
            }
        }
    }
}

@Preview(name = "Activated")
@Composable
private fun StatusCardActivatedPreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Not Activated")
@Composable
private fun StatusCardNotActivatedPreview() {
    StatusCard(state = previewHomeScreenState(ksuVersion = null, lkmMode = null), actions = HomeActions({}, {}))
}

@Preview(name = "Permissive")
@Composable
private fun StatusCardPermissivePreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive"),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Jailbreak")
@Composable
private fun StatusCardJailbreakPreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true),
        actions = HomeActions({}, {})
    )
}

private val previewSystemInfo = SystemInfo(
    kernelVersion = "6.1.0-android14-0-g123456789000-ab12345678",
    managerVersion = "3.0.0 (30000)",
    deviceModel = "Google Pixel 6 Pro",
    fingerprint = "google/raven/raven:14/AP1A.240305.019:user/release-keys",
    selinuxStatus = "Enforcing",
    seccompStatus = 2
)

private val previewUriHandler = object : UriHandler {
    override fun openUri(uri: String) {}
}

@Composable
private fun HomeScreenPreviewContent(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) {
    CompositionLocalProvider(LocalUriHandler provides previewUriHandler) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val actions = HomeActions({}, {})
            StatusCard(
                state = previewHomeScreenState(
                    ksuVersion = ksuVersion,
                    lkmMode = lkmMode,
                    isSafeMode = isSafeMode,
                    isLateLoadMode = isLateLoadMode,
                    selinuxStatus = selinuxStatus,
                ),
                actions = actions
            )
            InfoCard(previewSystemInfo.copy(selinuxStatus = selinuxStatus))
            SupportLinks(onOpenUrl = {})
        }
    }
}

@Preview(name = "Home Activated", showBackground = true)
@Composable
private fun HomeScreenActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true)
}

@Preview(name = "Home Not Activated", showBackground = true)
@Composable
private fun HomeScreenNotActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null)
}

@Preview(name = "Home Permissive", showBackground = true)
@Composable
private fun HomeScreenPermissivePreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive")
}

@Preview(name = "Home Jailbreak", showBackground = true)
@Composable
private fun HomeScreenJailbreakPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true)
}

private fun previewHomeScreenState(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) = HomeUiState(
    kernelVersion = KernelVersion(6, 1, 0),
    ksuVersion = ksuVersion,
    lkmMode = lkmMode,
    isLkmBundled = lkmMode == true,
    isManager = true,
    isManagerPrBuild = false,
    isKernelPrBuild = false,
    requiresNewKernel = false,
    requiresNewManager = false,
    isRootAvailable = ksuVersion != null,
    isSafeMode = isSafeMode,
    isLateLoadMode = isLateLoadMode,
    checkUpdateEnabled = false,
    latestVersionInfo = com.sevenk.core.ui.util.module.LatestVersionInfo(),
    currentManagerVersionCode = 10000,
    systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
    kernelUAPIVersion = 1,
    managerUAPIVersion = 1,
)
