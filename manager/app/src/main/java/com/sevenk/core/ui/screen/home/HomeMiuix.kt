package com.sevenk.core.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sevenk.core.KernelVersion
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.component.WarningLevel
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.dialog.rememberInfoDialog
import com.sevenk.core.ui.component.miuix.WarningCard
import com.sevenk.core.ui.component.rebootlistpopup.RebootListPopupMiuix
import com.sevenk.core.ui.component.statustag.StatusTag
import com.sevenk.core.ui.theme.LocalEnableBlur
import com.sevenk.core.ui.theme.isInDarkTheme
import com.sevenk.core.ui.util.BlurredBar
import com.sevenk.core.ui.util.miuixBarColor
import com.sevenk.core.ui.util.module.LatestVersionInfo
import com.sevenk.core.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.util.StatusDecoration
import com.sevenk.core.ui.util.LegacyDataMigration
import com.sevenk.core.ui.util.rememberMigrationRestartPending
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush

@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = miuixBarColor(blurActive)
    // 「界面扁平化」(flat_home):开启后首页卡片不画底色,只留文字和图标。
    // 由 MainActivity 统一提供(顶栏/底栏也读它),这里直接取即可。
    val flatHome = com.sevenk.core.ui.theme.LocalFlatUi.current
    // 「旧版 7kimisu」提示卡要用它拉起系统卸载界面(v2.2)
    val context = LocalContext.current
    // ⚠️ v0.13.157:这里原来有个 `stealthOn`(隐身时藏「关于 7kimisu」/「支持开发·了解 KernelSU」
    //    两张卡),**已删除**。用户判据:这两张卡是"一个**没有 root 的用户**点进来"就会看到的
    //    东西,隐身必须照常显示 —— 隐身只改"显示什么内容"(比如安装页里需要 root 的两个方法),
    //    不该把没 root 的人也看得到的卡片藏掉。
    //    (「伪装成内核未就绪」仍然由 HomeViewModel 把 UiState 归一化完成,
    //     见 HomeUiState.asStealthKernelNotReady —— 那是内容侧,不是外观侧。)
    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                // 注:左边距从 LazyColumn 移到下面具体卡片上，所有卡片统一左右 12dp。
                // (v0.13.28 曾让绿卡贴边满屏宽，v0.13.64 改回与其它卡片一致。)
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    // 所有卡片统一左右 12dp 边距(绿卡也一样)。
                    // 数值取自 StatusDecoration：设置里的「自裁取景」要用同一个值算卡片比例。
                    val sideInset = Modifier.fillMaxWidth()
                        .padding(horizontal = StatusDecoration.CARD_SIDE_INSET_DP.dp)
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = sideInset,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                // 这是「需要更新内核」的提示卡,点它就是去安装页;
                                // 隐身下它本来就不会出现(requiresNewKernel = isManager && …,隐身时 isManager=false)。
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
                        // 点一下 = 我知道了（以后不再提示，"不超过一次"）。
                        // 用卡片而不用弹窗的理由：① Miuix 的 WindowDialog 一次只能开一个
                        //（见 ui/component/miuix/ModuleIcon.kt 顶部注释），启动期已经有公告弹窗；
                        // ② "重启前别授权新 App"这件事**要一直看得见**，弹窗关掉就忘了。
                        if (rememberMigrationRestartPending()) {
                            WarningCard(
                                stringResource(id = R.string.migration_restart_notice),
                                onClick = { LegacyDataMigration.acknowledgeRestartNotice(context) },
                            )
                        }
                        }
                        // 绿色状态卡:跟其它卡片一样吃 12dp 边距(卡宽 = 屏宽 − 24dp)。
                        // 宽度沿革:v0.13.28 改成贴边(满屏宽) → v0.13.64 按用户要求改回原宽。
                        StatusCard(
                            state = state,
                            actions = actions,
                            flat = flatHome,
                            modifier = Modifier.padding(horizontal = StatusDecoration.CARD_SIDE_INSET_DP.dp),
                        )
                        // ⚠️ 「安装 / 更新内核」入口（2026-09-17 加）
                        // 以前只有绿卡本身可点，卡上又没写"安装"二字 —— 用户反馈"别的管理器有的基础功能
                        // 我们却没有"，查下来功能其实都在（安装页有：选择文件并修补 / 下载并修补 /
                        // 直接安装 / 安装到未使用槽位 / 使用本地 LKM 文件），**只是入口根本发现不了**。
                        // 所以这里单独摆一张写着字的卡。
                        // 隐身时它自然不出现(隐身下 lkmMode=null、managerNotRecognized=false),
                        // 且安装页本身已经显示成"真·没 root"的样子,不必在这里堵。
                        if (state.lkmMode == true || (state.managerNotRecognized && state.isRootAvailable)) {
                            Column(modifier = sideInset) {
                                InstallKernelCard(
                                    onClick = actions.onInstallClick,
                                    modifier = Modifier.fillMaxWidth(),
                                    flat = flatHome,
                                )
                            }
                        }
                        Column(
                            modifier = sideInset,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                        InfoCard(
                            systemInfo = state.systemInfo,
                            modifier = Modifier.fillMaxWidth(),
                            flat = flatHome,
                        )
                        // ⚠️ v0.13.157:这两张卡**隐身时照常显示**(原来外面套着 `if (!stealthOn)`,已撤)。
                        //    它们明写「7kimisu / 第三方 KernelSU 分支」和
                        //    「KernelSU is, and always will be, free and open source」——
                        //    但这正是"一个没 root 的用户"点进来会看到的东西,所以不该藏
                        //    (开屏公告同理,见 AnnouncementPopup)。
                        About7kCard(modifier = Modifier.fillMaxWidth(), flat = flatHome)
                        SupportLinks(
                            onOpenUrl = actions.onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                            flat = flatHome,
                        )
                        }
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
    val updateDialog = rememberConfirmDialog(onConfirm = { actions.onOpenUrl(newVersion.downloadUrl) })

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        WarningCard(
            message = stringResource(id = R.string.new_version_available, newVersion.versionCode),
            level = WarningLevel.Notice,
            onClick = {
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
        )
    }
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            actions = {
                RebootListPopupMiuix()
            },
            scrollBehavior = scrollBehavior
        )
    }
}

/** 自定义状态卡图片的全部可调项(打包一次读取,避免多个 remember) */
private data class DecorPrefs(
    val anchor: Int,
    val dim: Float,
    val biasX: Float,
    val biasY: Float,
    val zoom: Float,
    val hideText: Boolean,
)

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
    // 只影响「未安装 / 不支持」这两张兜底卡；绿色主卡永远是绿色
    flat: Boolean = false,
    modifier: Modifier = Modifier,
) {    Column {
        // ✅ 2026-09-21(v0.13.157):隐身时这张卡**照常可点**,点击行为与正常时完全一致
        //    (点「内核未就绪」弹原来的说明弹窗 —— 那正是"一个没 root 的用户"点进去会看到的东西)。
        //    v0.13.156 曾给隐身加一个只弹「暂时无法处理，请稍后再试」的首选分支,已撤。
        //    唯一保留的隐身差异是右侧的「越狱」按钮不画(它启动 late-load,是 root 管理器特征)。
        //    判定用 Stealth.looksStealthy()(v2.1,审计 P0-2):"不是明确读到 0 就藏",
        //    读失败 / 内核不支持时也藏 —— 免得把 root 特征(越狱按钮)露出来。
        //    不影响「从别的管理器转过来」(那时内核明确说隐身关着)的正常流程。
        val stealthOn = Stealth.looksStealthy()
        // 用户自定义的状态卡图片(没有就用默认的绿色对勾)
        val context = LocalContext.current
        val density = LocalDensity.current
        // 目标解码宽度:卡片现在是「屏宽 − 24dp」,按屏宽降采样略大一点,无害,
        // 平板壁纸 2560×1600 → 降到 1280×800 左右,内存 16MB → 4MB
        val targetWidthPx = with(density) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx()
        }
        val decoration by androidx.compose.runtime.produceState<ImageBitmap?>(
            initialValue = null, context, StatusDecoration.version, targetWidthPx
        ) {
            value = withContext(Dispatchers.IO) { StatusDecoration.load(context, targetWidthPx) }
        }
        // 自定义图的各可调项(取景位置 / 自裁偏移与缩放 / 压暗强度 / 是否隐藏文字)
        val decorRev = com.sevenk.core.ui.util.WallpaperPrefs.observe()
        val decorPrefs = androidx.compose.runtime.remember(decorRev) {
            val r = com.sevenk.core.data.repository.SettingsRepositoryImpl()
            DecorPrefs(
                anchor = r.statusDecorationAnchor,
                dim = r.statusDecorationDim,
                biasX = r.statusDecorationBiasX,
                biasY = r.statusDecorationBiasY,
                zoom = r.statusDecorationZoom,
                hideText = r.statusDecorationHideText,
            )
        }
        val decorAnchor = decorPrefs.anchor
        val decorDim = decorPrefs.dim
        val decorBiasX = decorPrefs.biasX
        val decorBiasY = decorPrefs.biasY
        val decorZoom = decorPrefs.zoom
        val decorHideText = decorPrefs.hideText

        // 「内核还没认主」的说明弹窗 —— 2026-09-16 修「点了没反应」时加的。
        // 这个状态下管理器拿不到 root(见 kernel/supercall/perm.c 的 allowed_for_su_authed),
        // 所以既不能替用户重启、也不能靠安装解决 —— 只能把道理讲清楚。
        val notRecognizedDialog = rememberInfoDialog()
        val notRecognizedTitle = stringResource(R.string.home_manager_not_recognized_title)
        val notRecognizedDetail = stringResource(R.string.home_manager_not_recognized_detail)
        // ⚠️ v0.13.157:原来这里还有一个 `stealthTapHint`(隐身专用的「暂时无法处理，请稍后再试」),
        //    连它引用的字符串资源 [R.string.home_stealth_tap_hint] 一起删掉了
        //    —— 隐身时点这一行现在和正常时一样,弹的就是上面这段原始说明。

        when {
            state.ksuVersion != null -> {
                val workingState = buildString {
                    if (state.isSafeMode) {
                        append(" [${stringResource(id = R.string.safe_mode)}]")
                    }
                    if (state.isLateLoadMode) {
                        append(" [${stringResource(id = R.string.jailbreak_mode)}]")
                    }
                }
                val workingMode = when (state.lkmMode) {
                    null -> null
                    true -> "LKM"
                    else -> "GKI"
                }
                // 状态卡文字固定,不再提供自定义
                val customStatusTitle = STATUS_CARD_TEXT
                val workingText = (if (customStatusTitle.isBlank()) "" else customStatusTitle + workingState).trim()

                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        // 绿卡高度:原来内容撑开(约 78dp)。现在给一个下限 ——— 已放大到 4.3 倍左右,
                        // 当版高度 332dp(= 上一版 249dp 再 × 4/3)。想再大/再小改 StatusDecoration.CARD_HEIGHT_DP 就行。
                        // 用 heightIn(min) 而不是固定高度:自定义标题较长时卡片能自己变高,不会裁掉。
                        colors = CardDefaults.defaultColors(
                            // 状态卡永远是自己的底色（绿卡）：
                            // 「界面扁平化」只影响信息卡/关于/支持开发那些，不动这张主卡。
                            // 自定义图片优先（图片铺满整张卡，不需要底色）。
                            color = when {
                                decoration != null -> Color.Transparent
                                isDynamicColor -> colorScheme.secondaryContainer
                                isInDarkTheme() -> Color(0xFF1A3825)
                                else -> Color(0xFFDFFAE4)
                            }
                        ),
                        onClick = {
                            if (!state.isLateLoadMode) {
                                actions.onInstallClick()
                            }
                        },
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt,
                        modifier = Modifier.fillMaxWidth().heightIn(min = StatusDecoration.CARD_HEIGHT_DP.dp)
                            ,
                    ) {
                        Box {
                            val custom = decoration
                            // 关键:Box 的尺寸得有人撑起来。
                            // 图片和压暗层都是 matchParentSize(按定义不参与定尺寸),
                            // 文字又被「只留图片」隐藏后 → Box 塔成 0×0 → 图看不见,
                            // 而卡底色又是透明(自定义图分支) → 整张卡看起来全透明。
                            // 这个空占位负责撑尺寸(内在高度为 0,不会把卡撑高)。
                            Spacer(Modifier.fillMaxSize())
                            // 自定义图时给文字加描边:这样即使不压暗也看得清
                            val decorTextStyle = if (custom != null) {
                                LocalTextStyle.current.copy(
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.78f),
                                        offset = Offset(2f, 2f),
                                        blurRadius = 6f,
                                    )
                                )
                            } else {
                                LocalTextStyle.current
                            }
                            if (custom != null) {
                                // 自定义图:铺满整张卡当背景(取景/自裁可选),再按设定压暗保证文字可读
                                com.sevenk.core.ui.util.StatusDecorationImage(
                                    bitmap = custom,
                                    biasX = if (decorAnchor == 3) decorBiasX else 0f,
                                    biasY = when (decorAnchor) {
                                        1 -> -1f
                                        2 -> 1f
                                        3 -> decorBiasY
                                        else -> 0f
                                    },
                                    zoom = decorZoom,
                                    modifier = Modifier.matchParentSize(),
                                )
                                if (decorDim > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.Black.copy(alpha = decorDim),
                                                        Color.Black.copy(alpha = decorDim * 0.33f),
                                                    )
                                                )
                                            )
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .offset(27.dp, 31.dp),
                                    contentAlignment = Alignment.BottomEnd
                                ) {
                                    Icon(
                                        modifier = Modifier.size(110.dp),
                                        imageVector = Icons.Rounded.CheckCircleOutline,
                                        tint = if (isDynamicColor) {
                                            colorScheme.primary.copy(alpha = 0.8f)
                                        } else {
                                            Color(0xFF36D167)
                                        },
                                        contentDescription = null
                                    )
                                }
                            }
                            CompositionLocalProvider(
                                LocalContentColor provides
                                    if (custom != null) Color.White else LocalContentColor.current
                            ) {
                            // 自定义图 + 开了"只留图片"→ 文字全部不画
                            if (custom == null || !decorHideText) {
                            if (workingMode != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp, 10.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    Text(
                                        text = workingMode,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        style = decorTextStyle,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    if (workingText.isNotBlank()) {
                                        Text(
                                            text = workingText,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            style = decorTextStyle,
                                        )
                                        Spacer(Modifier.height(1.dp))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(
                                                R.string.home_working_version,
                                                "${state.ksuVersion}-${state.kernelUAPIVersion}"
                                            ),
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontSize = 15.sp,
                                            style = decorTextStyle,
                                        )
                                        if (state.showCustomLkmBadge) {
                                            Spacer(Modifier.width(8.dp))
                                            StatusTag(
                                                label = stringResource(R.string.home_lkm_custom),
                                                contentColor = if (isDynamicColor) {
                                                    colorScheme.onTertiaryContainer
                                                } else if (isInDarkTheme()) {
                                                    Color(0xFFB8E8C5)
                                                } else {
                                                    Color(0xFF164A29)
                                                },
                                                backgroundColor = if (isDynamicColor) {
                                                    colorScheme.tertiaryContainer
                                                } else if (isInDarkTheme()) {
                                                    Color(0xFF315D3E)
                                                } else {
                                                    Color(0xFFB8E8C5)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            }
                            }
                        }
                    }
                }
            }

            state.kernelVersion.isGKI() -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier.weight(1f),
                        // ⚠️ 2026-09-16 修两轮:
                        //   ① 以前这里让 onClick「什么都不做」→ 用户点下去毫无反应,以为界面坏了。
                        //   ② 后来改成一律弹「重启一次」→ 又把**从别的管理器转过来**的人堵死了
                        //      (他们需要的是安装,不是重启)。
                        //   现在按「本应用有没有拿到 root」分流 —— 这是唯一可靠的判据:
                        //     我们的内核在跑但没认主时,内核不会给本应用 root
                        //     (kernel/supercall/perm.c 的 allowed_for_su_authed);
                        //     而从别的 root 管理器授权后就有 root。
                        // ✅ 这张卡**照常可点** —— 点进去的安装页已经显示成
                        //    "真·没 root 的设备"该有的样子(没有「直接安装(推荐)」/「安装到未使用槽位」,
                        //    只有「选择文件并修补 / 下载文件并修补 / 使用本地 LKM 文件」),
                        //    所以不露馅;反倒是"点不了"本身就可疑(v0.13.149 的教训)。
                        //    ⚠️ 唯一保留的隐身差异:右边的「越狱」按钮不画(见下面 endActions)。
                        //
                        // ⚠️ v0.13.157:隐身时**不再是特例**。v0.13.156 曾在这里插一个
                        //    `stealthOn ->` 首选分支,只弹一句「暂时无法处理，请稍后再试」、
                        //    同时堵住安装页入口 —— **已撤**。用户判据:点「内核未就绪」弹出的那段
                        //    原始说明,正是一个**没有 root 的用户**点进来会看到的东西,
                        //    所以隐身时必须弹它(而不是弹一句"什么都不漏"的自造提示)。
                        onClick = {
                            when {
                                state.isLateLoadMode -> Unit
                                // 有 root → 直达安装页:授权 → 点一下 → 直接安装 → 重启
                                state.managerNotRecognized && state.isRootAvailable ->
                                    actions.onInstallClick()
                                // 没 root → 装不了,把两种情况讲清楚(单按钮告知弹窗)
                                state.managerNotRecognized -> notRecognizedDialog.show(
                                    notRecognizedTitle,
                                    notRecognizedDetail,
                                )
                                else -> actions.onInstallClick()
                            }
                        },
                        // 有按压反馈才像个能点的东西(以前这里也是 false,点了彻底没动静)
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt,
                        colors = if (flat) CardDefaults.defaultColors(color = Color.Transparent)
                                 else CardDefaults.defaultColors(),
                    ) {
                        Box {
                        BasicComponent(
                            // 标题不再是「未安装」:装好了却写"没装"是误导。
                            // 有 root 时直接说「可以安装了」——把下一步摆在脸上。
                            title = if (state.managerNotRecognized) {
                                if (state.isRootAvailable) stringResource(R.string.home_can_install)
                                else notRecognizedTitle
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
                                    stringResource(R.string.home_not_installed),
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = colorScheme.onBackground,
                                )
                            },
                            endActions = {
                                // 隐身时「越狱」按钮不画(它启动 late-load,是 root 管理器特征)
                                if (!stealthOn && state.isSELinuxPermissive) {
                                    TextButton(
                                        text = stringResource(R.string.home_jailbreak),
                                        onClick = actions.onJailbreakClick,
                                        colors = ButtonDefaults.textButtonColorsPrimary()
                                    )
                                }
                            }
                        )
                        }
                    }
                }
            }

            else -> {
                Card(
                    // 同样是安装入口(内核版本不认识时也走安装);照常可点,
                    // 安装页本身已经显示成"真·没 root"的样子(见 InstallScreen 里的说明)
                    // ⚠️ v0.13.157:隐身时**不再是特例**(v0.13.156 加在这里的 stealthOn 堵法已撤)。
                    onClick = {
                        if (!state.isLateLoadMode) {
                            actions.onInstallClick()
                        }
                    },
                    showIndication = !state.isLateLoadMode,
                    pressFeedbackType = PressFeedbackType.Tilt,
                    colors = if (flat) CardDefaults.defaultColors(color = Color.Transparent)
                             else CardDefaults.defaultColors(),
                ) {
                    Box {
                    BasicComponent(
                        title = stringResource(R.string.home_unsupported),
                        summary = stringResource(R.string.home_unsupported_reason),
                        startAction = {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                stringResource(R.string.home_unsupported),
                                modifier = Modifier.padding(end = 16.dp),
                                tint = colorScheme.onBackground,
                            )
                        }
                    )
                    }
                }
            }
        }
    }
}

/** 首页卡片:开启「界面扁平化」时不画底色,只留文字和图标 */
@Composable
private fun HomeCard(
    flat: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (flat) {
        Card(
            modifier = modifier,
            colors = CardDefaults.defaultColors(color = Color.Transparent),
            content = content,
        )
    } else {
        Card(modifier = modifier, content = content)
    }
}

@Composable
private fun InstallKernelCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    HomeCard(flat = flat, modifier = modifier) {
        ArrowPreference(
            title = "安装 / 更新内核",
            summary = "点这里修补 init_boot：可选文件修补、也可直接安装",
            startAction = {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "安装内核",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun About7kCard(
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    val dialog = rememberConfirmDialog()
    HomeCard(flat = flat, modifier = modifier) {
        ArrowPreference(
            title = "关于 7kimisu",
            summary = "第三方 KernelSU 分支 · 点这里看说明",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "关于 7kimisu",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                dialog.showConfirm(
                    title = "关于 7kimisu",
                    content = ABOUT_7K_TEXT,
                    markdown = true,
                    confirm = "知道了",
                )
            },
        )
    }
}

@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    val learnMoreUrl = stringResource(R.string.home_learn_kernelsu_url)

    HomeCard(flat = flat, modifier = modifier) {
        ArrowPreference(
            title = stringResource(R.string.home_support_title),
            summary = stringResource(R.string.home_support_content),
            startAction = {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = stringResource(R.string.home_support_title),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl("https://patreon.com/weishu") },
        )
        ArrowPreference(
            title = stringResource(R.string.home_learn_kernelsu),
            summary = stringResource(R.string.home_click_to_learn_kernelsu),
            startAction = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = stringResource(R.string.home_learn_kernelsu),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl(learnMoreUrl) },
        )
    }
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeCard(flat = flat, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Tag,
                    title = stringResource(R.string.home_manager_version),
                    content = systemInfo.managerVersion,
                )
                InfoText(
                    icon = Icons.Filled.DeveloperBoard,
                    title = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                    modifier = Modifier,
                )
                InfoText(
                    icon = Icons.Filled.Smartphone,
                    title = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
                InfoText(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                    bottomPadding = 0.dp,
                )
            }
        }
        HomeCard(flat = flat, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
                InfoText(
                    icon = Icons.Filled.FilterList,
                    title = stringResource(R.string.home_seccomp_status),
                    content = seccompDisplay,
                    bottomPadding = 0.dp,
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
    kernelVersion = "6.12.23-android16-5-g123456789000-abogki123456789-4k",
    managerVersion = "3.0.0 (30000)",
    deviceModel = "Xiaomi 17 Pro Max",
    fingerprint = "Xiaomi/popsicle/popsicle:16/BQ2A.250705.001-BP2A.250605.031.A3/OS3.0.313.0.WPBCNXM:user/release-keys",
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            InfoCard(
                systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
                modifier = Modifier.fillMaxWidth(),
            )
            SupportLinks(
                onOpenUrl = {},
                modifier = Modifier.fillMaxWidth(),
            )
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
    latestVersionInfo = LatestVersionInfo(),
    currentManagerVersionCode = 10000,
    systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
    kernelUAPIVersion = 1,
    managerUAPIVersion = 1,
)
