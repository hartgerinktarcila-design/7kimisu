package com.sevenk.core.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sevenk.core.R
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.component.KsuIsValid
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.dialog.rememberLoadingDialog
import com.sevenk.core.ui.component.miuix.SendLogDialog
import com.sevenk.core.ui.component.uninstalldialog.UninstallDialog
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.security.findActivity
import com.sevenk.core.ui.theme.LocalEnableBlur
import com.sevenk.core.ui.theme.miuixDialogColor
import com.sevenk.core.ui.util.BlurredBar
import com.sevenk.core.ui.util.AnnouncementPrefs
import com.sevenk.core.ui.util.miuixBarColor
import com.sevenk.core.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SevereCold
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewInAr
import com.sevenk.core.ui.util.StatusDecoration
import com.sevenk.core.ui.util.NavIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Wallpaper
import top.yukonga.miuix.kmp.preference.SliderPreference
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.ColorLens

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = miuixBarColor(blurActive)
    val loadingDialog = rememberLoadingDialog()
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var hasDecoration by remember { mutableStateOf(StatusDecoration.hasCustom(context)) }
    val pickDecoration = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            StatusDecoration.save(context, uri)
            hasDecoration = StatusDecoration.hasCustom(context)
        }
    }

    // 个性化壁纸
    val prefsRepo = remember { com.sevenk.core.data.repository.SettingsRepositoryImpl() }
    // 自定义状态卡图片的三个可调项(取景/压暗/是否隐藏文字)
    val decorDimLevels = remember { listOf(0f, 0.3f, 0.55f, 0.8f) }
    var decorAnchor by remember { mutableStateOf(prefsRepo.statusDecorationAnchor) }
    var decorDimIndex by remember {
        mutableStateOf(
            decorDimLevels.indexOfFirst { kotlin.math.abs(it - prefsRepo.statusDecorationDim) < 0.02f }
                .takeIf { it >= 0 } ?: 2
        )
    }
    var decorHideText by remember { mutableStateOf(prefsRepo.statusDecorationHideText) }
    var decorBiasX by remember { mutableStateOf(prefsRepo.statusDecorationBiasX) }
    var decorBiasY by remember { mutableStateOf(prefsRepo.statusDecorationBiasY) }
    var decorZoom by remember { mutableStateOf(prefsRepo.statusDecorationZoom) }
    var showCropDialog by remember { mutableStateOf(false) }
    // 自定义导航图标(主页/超级用户/模块/设置)
    var navIconCircle by remember { mutableStateOf(prefsRepo.navIconCircle) }
    var navIconTint by remember { mutableStateOf(prefsRepo.navIconTint) }
    var navIconVersion by remember { mutableStateOf(NavIcons.version) }
    var pendingNavKey by remember { mutableStateOf<String?>(null) }
    val pickNavIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val key = pendingNavKey
        if (uri != null && key != null) {
            val ok = NavIcons.save(context, key, uri)
            navIconVersion = NavIcons.version
            if (!ok) {
                android.widget.Toast.makeText(context, "这张图读不出来,换一张试试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        pendingNavKey = null
    }
    val wallVersion = com.sevenk.core.ui.util.WallpaperStore.version
    // 竖屏那份 = 老的那份(kind 不变);横屏那份是新加的第二份,没设时横屏回退用竖屏那张
    val wallpaperKind = com.sevenk.core.ui.util.WallpaperStore.kind(com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT)
    val wallpaperLandKind = com.sevenk.core.ui.util.WallpaperStore.kind(com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE)
    val dimLevels = remember { listOf(0f, 0.2f, 0.35f, 0.55f) }
    val blurLevels = remember { listOf(0f, 8f, 16f, 28f) }
    var dimIndex by remember {
        mutableStateOf(dimLevels.indexOfFirst { kotlin.math.abs(it - prefsRepo.wallpaperDim) < 0.02f }
            .coerceAtLeast(0))
    }
    var blurIndex by remember {
        mutableStateOf(blurLevels.indexOfFirst { kotlin.math.abs(it - prefsRepo.wallpaperBlur) < 0.5f }
            .coerceAtLeast(0))
    }
    var translucent by remember { mutableStateOf(prefsRepo.uiTranslucent) }
    var seedEnabled by remember { mutableStateOf(prefsRepo.wallpaperSeed) }
    // 下雪特效
    var snowEnabled by remember { mutableStateOf(prefsRepo.snowEnabled) }
    var snowSize by remember { mutableStateOf(prefsRepo.snowSize) }
    var snowSpeed by remember { mutableStateOf(prefsRepo.snowSpeed) }
    // 巨魔雨
    var trollRainEnabled by remember { mutableStateOf(prefsRepo.trollRainEnabled) }
    var trollRainSize by remember { mutableStateOf(prefsRepo.trollRainSize) }
    var trollRainSpeed by remember { mutableStateOf(prefsRepo.trollRainSpeed) }
    // 重力感应(倾斜手机)
    var gravityEnabled by remember { mutableStateOf(prefsRepo.gravityEnabled) }
    var gravityMode by remember { mutableStateOf(prefsRepo.gravityMode) }
    // 隐身密令(拨号数字)
    var stealthCode by remember { mutableStateOf(prefsRepo.stealthCode) }
    // 网页管理器：服务在 ksud（root 守护进程）里，这里只管开关与地址。
    // 地址要起 root shell 才能读到，所以异步取，别卡住界面。
    val webAdminScope = rememberCoroutineScope()
    var webAdminEnabled by remember { mutableStateOf(prefsRepo.webAdminEnabled) }
    var webAdminUrl by remember { mutableStateOf("") }
    LaunchedEffect(webAdminEnabled) {
        webAdminUrl = if (webAdminEnabled) {
            withContext(Dispatchers.IO) {
                // 🟢 v2.15：启动期那次 `syncPref(true)` 已从 MainActivity.onCreate 删掉
                //    （启动链不许发 root shell，理由见 MainActivity 顶部的结构性约束）。
                //    改成"用户真进设置页 / 真打开这个开关"时再同步 —— 语义不变：
                //    升级安装后 ksud 侧还是旧开关/旧二进制，这里一进页面就对齐。
                com.sevenk.core.ui.util.WebAdminCli.syncPref(true)
                com.sevenk.core.ui.util.WebAdminCli.url()
            }
        } else {
            ""
        }
    }
    // 网页管理器的界面文案（4 语言）。⚠️ 这些必须在**组合期**取好：
    // onClick / Toast 的 lambda 不是 @Composable，里面只能读 context.getString(...)。
    val waTitle = stringResource(id = R.string.webadmin_title)
    val waSummaryOff = stringResource(id = R.string.webadmin_summary_off)
    val waReading = stringResource(id = R.string.webadmin_reading)
    val waOpen = stringResource(id = R.string.webadmin_open)
    val waOpenSummary = stringResource(id = R.string.webadmin_open_summary)
    val waCopy = stringResource(id = R.string.webadmin_copy)
    val waCopySummary = stringResource(id = R.string.webadmin_copy_summary)
    val waCopyOk = stringResource(id = R.string.webadmin_copy_ok)
    val waCopyFail = stringResource(id = R.string.webadmin_copy_fail)
    val waReset = stringResource(id = R.string.webadmin_reset)
    val waResetSummary = stringResource(id = R.string.webadmin_reset_summary)
    val waResetConfirm = stringResource(id = R.string.webadmin_reset_confirm)
    val waResetFailed = stringResource(id = R.string.webadmin_reset_failed)
    val waDiagnose = stringResource(id = R.string.webadmin_diagnose)
    val waDiagnoseSummary = stringResource(id = R.string.webadmin_diagnose_summary)
    val waDiagnoseTitle = stringResource(id = R.string.webadmin_diagnose_title)
    val waCopyAll = stringResource(id = R.string.webadmin_copy_all)
    val waCopyAllOk = stringResource(id = R.string.webadmin_copy_all_ok)
    val waKsudFailed = stringResource(id = R.string.webadmin_ksud_failed)
    // 密令可能在 /data/adb/sevenk/stealth_code 里(跨卸载备份)—— 优先显示那个真实生效的值
    LaunchedEffect(Unit) {
        val real = withContext(Dispatchers.IO) {
            com.sevenk.core.ui.security.StealthCodeStore.sync()
            com.sevenk.core.ui.security.StealthCodeStore.effectiveCode(context)
        }
        if (real.isNotBlank() && real != stealthCode) stealthCode = real
    }
    // 界面扁平化(去掉顶栏/底栏/卡片的底色)
    var flatHome by remember { mutableStateOf(prefsRepo.flatHome) }
    // v2.26「不再显示公告」：打开后开屏公告永不再弹。
    // 状态**不走 prefsRepo** —— 公告的 pref 键只由 `ui/util/AnnouncementPrefs.kt` 定义一处，
    // 免得同一个键在两个文件里各写一遍、哪天改歪一个就"开关失灵"。
    var announcementNeverShow by remember { mutableStateOf(AnnouncementPrefs.neverShow(context)) }
    // 壁纸可见度 0~1:1 = 界面几乎全透明(壁纸完全露出来),0 = 界面不透明
    var visibility by remember {
        mutableStateOf(((1f - prefsRepo.uiTranslucentAlpha) / 0.98f).coerceIn(0f, 1f))
    }
    // 壁纸选取器:竖屏/横屏共用两个 launcher,靠 pendingWallSlot 记住"这次是给哪一份选的"
    var pendingWallSlot by remember {
        mutableStateOf(com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT)
    }
    val pickWallpaperImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 🔴（v2.19）WallpaperStore.save 是"整份文件复制 + 回读校验 + 解码取主色"的重活，
            // 旧写法在 launcher 回调（**主线程**）里同步跑 ⇒ 视频壁纸/大图直接 ANR。
            // 照同文件 applyBuiltin 的写法挪到 AppExecutors.io，Toast 回主线程弹。
            // slot 在回调（主线程）先取出来，避免 IO 线程读 Compose 快照状态。
            val slot = pendingWallSlot
            com.sevenk.core.core.utils.AppExecutors.io.execute {
                val err = com.sevenk.core.ui.util.WallpaperStore.save(context, uri, isVideo = false, slot = slot)
                if (err != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    val pickWallpaperVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 🔴（v2.19）同上：视频壁纸 save 要复制整份视频 + 回读，主线程跑必 ANR。
            val slot = pendingWallSlot
            com.sevenk.core.core.utils.AppExecutors.io.execute {
                val err = com.sevenk.core.ui.util.WallpaperStore.save(context, uri, isVideo = true, slot = slot)
                if (err != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
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
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.SystemUpdate,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                        KsuIsValid {
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_module_check_update),
                                summary = stringResource(id = R.string.settings_check_update_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.SystemUpdateAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_check_update),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.checkModuleUpdate,
                                onCheckedChange = actions.onSetCheckModuleUpdate
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            startAction = {
                                Icon(
                                    Icons.Rounded.DisplaySettings,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_ui_mode),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onSelectedIndexChange = actions.onSetUiModeIndex
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_theme),
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_theme),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTheme
                        )
                        ArrowPreference(
                            title = "状态卡图片",
                            summary = if (hasDecoration) "已自定义(点一下换一张)" else "换掉首页那个绿色对勾",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Image,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "状态卡图片",
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { pickDecoration.launch("image/*") }
                        )
                        if (hasDecoration) {
                            ArrowPreference(
                                title = "恢复默认图片",
                                summary = "用回内置的默认图片",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Restore,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "恢复默认图片",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    StatusDecoration.clear(context)
                                    hasDecoration = StatusDecoration.hasCustom(context)
                                }
                            )
                            // 平板壁纸比卡片高(卡片是整屏宽×187dp,约 2.1:1),
                            // 只能截一条出来 —— 这里让用户选截哪一条。
                            OverlayDropdownPreference(
                                title = "图片取景",
                                summary = "平板壁纸比卡片高,选看哪一条;选「自裁」可以自己拖",
                                items = listOf("居中", "偏上(露天空/头发)", "偏下(露地面/水面)", "自裁(自己拖/缩放)"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.CropFree,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "图片取景",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = decorAnchor.coerceIn(0, 3),
                                onSelectedIndexChange = { idx ->
                                    if (idx == 3) {
                                        // 自裁:先开弹窗调,确定后才落盘(decorAnchor 在那时置 3)
                                        showCropDialog = true
                                    } else {
                                        decorAnchor = idx
                                        prefsRepo.statusDecorationAnchor = idx
                                    }
                                }
                            )
                            // 缩放:独立于取景方式,居中/偏上/偏下/自裁都能缩
                            SliderPreference(
                                title = "图片缩放",
                                summary = "现在 ${"%.1f".format(decorZoom)}×（往右更大;自裁弹窗里也能双指缩放）",
                                value = ((decorZoom - 1f) / 3f).coerceIn(0f, 1f),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.ZoomIn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "图片缩放",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onValueChange = {
                                    val z = 1f + it * 3f
                                    decorZoom = z
                                    prefsRepo.statusDecorationZoom = z
                                }
                            )
                            OverlayDropdownPreference(
                                title = "图片压暗",
                                summary = "压暗一点,上面的字更清楚(关掉则靠文字描边)",
                                items = listOf("关闭", "轻", "中", "重"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Brightness4,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "图片压暗",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = decorDimIndex,
                                onSelectedIndexChange = {
                                    decorDimIndex = it
                                    prefsRepo.statusDecorationDim = decorDimLevels[it]
                                }
                            )
                            SwitchPreference(
                                title = "只留图片",
                                summary = "不显示标题/版本/LKM,整张卡只有图",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.TextFields,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "只留图片",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = decorHideText,
                                onCheckedChange = {
                                    decorHideText = it
                                    prefsRepo.statusDecorationHideText = it
                                }
                            )
                        }

                        // 「自裁取景」弹窗:拖动调位置、双指缩放,确定后落盘
                        if (showCropDialog) {
                            DecorationCropDialog(
                                onDismiss = { showCropDialog = false },
                                onApply = { bx, by, zm ->
                                    decorBiasX = bx
                                    decorBiasY = by
                                    decorZoom = zm
                                    prefsRepo.statusDecorationBiasX = bx
                                    prefsRepo.statusDecorationBiasY = by
                                    prefsRepo.statusDecorationZoom = zm
                                    decorAnchor = 3
                                    prefsRepo.statusDecorationAnchor = 3
                                    showCropDialog = false
                                },
                            )
                        }

                        // ── 导航图标自定义 ───────────────────────────────
                        // 说明:GitHub 上那些好看的二次元图标大多是画师作品、没有可再分发的开源许可,
                        // 所以我们不内置,而是把选择权交给用户 —— 你想用哪个角色都行。
                        val navIconLabels = listOf("主页", "超级用户", "模块", "设置")
                        val navIconPreviews = listOf(
                            Icons.Rounded.Cottage,
                            Icons.Rounded.Security,
                            Icons.Rounded.Extension,
                            Icons.Rounded.Settings,
                        )
                        navIconLabels.forEachIndexed { idx, name ->
                            val key = NavIcons.keys[idx]
                            val has = remember(navIconVersion) { NavIcons.has(context, key) }
                            // 右侧小预览:必须显式写类型(Miuix 的 endActions 不接受 null),
                            // 没自定义就给个空 lambda,那就只剩箭头
                            val preview: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit =
                                if (has) {
                                    { NavIconPreview(key = key) }
                                } else {
                                    {}
                                }
                            ArrowPreference(
                                title = "导航图标 · $name",
                                summary = if (has) "已自定义(点一下换一张)" else "当前是默认图标(点一下选图)",
                                startAction = {
                                    Icon(
                                        navIconPreviews[idx],
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "导航图标 · $name",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                endActions = preview,
                                onClick = {
                                    pendingNavKey = key
                                    pickNavIcon.launch("image/*")
                                }
                            )
                        }
                        SwitchPreference(
                            title = "导航图标裁成圆形",
                            summary = "头像/人物图开这个最好看",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Circle,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "裁成圆形",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = navIconCircle,
                            onCheckedChange = {
                                navIconCircle = it
                                prefsRepo.navIconCircle = it
                            }
                        )
                        SwitchPreference(
                            title = "导航图标着色",
                            summary = "按主题色染成单色(适合剪影图;彩色美少女图建议关闭)",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "导航图标着色",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = navIconTint,
                            onCheckedChange = {
                                navIconTint = it
                                prefsRepo.navIconTint = it
                            }
                        )
                        if (remember(navIconVersion) { NavIcons.hasAny(context) }) {
                            ArrowPreference(
                                title = "恢复默认导航图标",
                                summary = "四个图标全部换回自带的",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Restore,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "恢复默认导航图标",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    NavIcons.clearAll(context)
                                    navIconVersion = NavIcons.version
                                }
                            )
                        }

                        SwitchPreference(
                            title = "界面扁平化",
                            summary = "顶栏/底栏/信息卡片都只留文字和图标,不画底色(绿色状态卡和模块页不受影响)",
                            startAction = {
                                Icon(
                                    Icons.Rounded.LayersClear,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "界面扁平化",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = flatHome,
                            onCheckedChange = {
                                flatHome = it
                                prefsRepo.flatHome = it
                            }
                        )
                        // 🔴 v2.26（用户点名要的开关）：打开后开屏公告**永远不再弹**。
                        //    关着时公告也**不再每次冷启动都弹** —— 只在"有新公告"时弹一次
                        //    （判据见 ui/util/AnnouncementPrefs.kt 与 AnnouncementPopup.kt）。
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_announcement_never_show),
                            summary = stringResource(id = R.string.settings_announcement_never_show_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Campaign,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_announcement_never_show),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = announcementNeverShow,
                            onCheckedChange = {
                                announcementNeverShow = it
                                AnnouncementPrefs.setNeverShow(context, it)
                            }
                        )
                        SwitchPreference(
                            title = "下雪效果",
                            summary = "整个界面上下大雪(氛围特效,可随时关掉)",
                            startAction = {
                                Icon(
                                    Icons.Rounded.SevereCold,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "下雪效果",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = snowEnabled,
                            onCheckedChange = {
                                snowEnabled = it
                                prefsRepo.snowEnabled = it
                            }
                        )
                        if (snowEnabled) {
                            SliderPreference(
                                title = "雪花大小",
                                summary = "现在 ${"%.1f".format(snowSize)}×（往右更大）",
                                value = ((snowSize - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    snowSize = s
                                    prefsRepo.snowSize = s
                                }
                            )
                            SliderPreference(
                                title = "雪花速度",
                                summary = "现在 ${"%.1f".format(snowSpeed)}×（往右更快）",
                                value = ((snowSpeed - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    snowSpeed = s
                                    prefsRepo.snowSpeed = s
                                }
                            )
                        }
                        SwitchPreference(
                            title = "巨魔雨",
                            summary = "蓝巨魔图标翻滚着下雨",
                            startAction = {
                                Icon(
                                    Icons.Rounded.ViewInAr,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "巨魔雨",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = trollRainEnabled,
                            onCheckedChange = {
                                trollRainEnabled = it
                                prefsRepo.trollRainEnabled = it
                            }
                        )
                        if (trollRainEnabled) {
                            SliderPreference(
                                title = "巨魔大小",
                                summary = "现在 ${"%.1f".format(trollRainSize)}×（往右更大）",
                                value = ((trollRainSize - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    trollRainSize = s
                                    prefsRepo.trollRainSize = s
                                }
                            )
                            SliderPreference(
                                title = "巨魔速度",
                                summary = "现在 ${"%.1f".format(trollRainSpeed)}×（往右更快）",
                                value = ((trollRainSpeed - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    trollRainSpeed = s
                                    prefsRepo.trollRainSpeed = s
                                }
                            )
                        }
                        SwitchPreference(
                            title = "重力感应",
                            summary = "倾斜手机,雪花和巨魔会朝低的一侧加速流下",
                            startAction = {
                                Icon(
                                    Icons.Rounded.ScreenRotation,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "重力感应",
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = gravityEnabled,
                            onCheckedChange = {
                                gravityEnabled = it
                                prefsRepo.gravityEnabled = it
                            }
                        )
                        if (gravityEnabled) {
                            OverlayDropdownPreference(
                                title = "重力方向校准",
                                summary = "倾斜方向反了就换一个（一般用不到）",
                                items = listOf("正常", "左右翻转", "上下翻转", "反转 180°"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.ScreenRotation,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "重力方向校准",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = gravityMode.coerceIn(0, 3),
                                onSelectedIndexChange = {
                                    gravityMode = it
                                    prefsRepo.gravityMode = it
                                }
                            )
                        }
                        val wallpaperInfo = remember(wallVersion, prefsRepo) {
                            val f = com.sevenk.core.ui.util.WallpaperStore.file(
                                context, com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT
                            )
                            if (f == null) "" else "  ${f.length() / 1024}KB"
                        }
                        val wallpaperLandInfo = remember(wallVersion, prefsRepo) {
                            val f = com.sevenk.core.ui.util.WallpaperStore.file(
                                context, com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                            )
                            if (f == null) "" else "  ${f.length() / 1024}KB"
                        }
                        // ⚠️ v0.13.157:原来这里有一条「隐身期间个性化不生效(素颜)」的提示,已删除
                        //    —— 隐身不再影响任何个性化(壁纸/特效/配色/扁平/自定义图片照常生效,
                        //    见 security/Stealth.kt 顶部注释),所以这句提示本身已经不成立。
                        // ⚠️ v0.13.160:分朝向。原来这一项(「背景壁纸」)改叫「竖屏壁纸」,
                        //    它用的还是老的那份设置(老用户数据不动);横屏是新加的第二份,
                        //    没单独设时横屏会自动沿用竖屏这张 —— 所以升级后观感不变。
                        ArrowPreference(
                            title = "竖屏壁纸",
                            summary = when {
                                com.sevenk.core.ui.util.WallpaperStore.looksBroken(
                                    com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT
                                ) -> "⚠️ 壁纸文件已丢失,请重新选一张(点一下)"
                                wallpaperKind == "image" -> "当前:图片$wallpaperInfo(点一下换一张)"
                                wallpaperKind == "video" -> "当前:视频$wallpaperInfo(点一下换成图片)"
                                else -> "选一张图当竖屏背景(横屏没单独设时也用它)"
                            },
                            startAction = {
                                Icon(
                                    Icons.Rounded.Wallpaper,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "竖屏壁纸",
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = {
                                pendingWallSlot = com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT
                                pickWallpaperImage.launch("image/*")
                            }
                        )
                        ArrowPreference(
                            title = "竖屏视频",
                            summary = if (wallpaperKind == "video") "当前:视频(点一下换一段)"
                            else "选一段视频当竖屏背景(静音循环播放)",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Movie,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "竖屏视频",
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = {
                                pendingWallSlot = com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT
                                pickWallpaperVideo.launch("video/*")
                            }
                        )
                        // ── 横屏壁纸(v0.13.160 新增)──
                        ArrowPreference(
                            title = "横屏壁纸",
                            summary = when {
                                com.sevenk.core.ui.util.WallpaperStore.looksBroken(
                                    com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                                ) -> "⚠️ 横屏壁纸文件已丢失,请重新选一张(点一下)"
                                wallpaperLandKind == "image" -> "当前:图片$wallpaperLandInfo(点一下换一张)"
                                wallpaperLandKind == "video" -> "当前:视频$wallpaperLandInfo(点一下换成图片)"
                                else -> "不设就横屏沿用竖屏那张(点一下选图)"
                            },
                            startAction = {
                                Icon(
                                    Icons.Rounded.ScreenRotation,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "横屏壁纸",
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = {
                                pendingWallSlot = com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                                pickWallpaperImage.launch("image/*")
                            }
                        )
                        ArrowPreference(
                            title = "横屏视频",
                            summary = if (wallpaperLandKind == "video") "当前:视频(点一下换一段)"
                            else "选一段视频当横屏背景(静音循环播放)",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Movie,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "横屏视频",
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = {
                                pendingWallSlot = com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                                pickWallpaperVideo.launch("video/*")
                            }
                        )
                        if (wallpaperKind != "none" || wallpaperLandKind != "none") {
                            OverlayDropdownPreference(
                                title = "壁纸暗度",
                                summary = "压暗一点,上面的字更清楚",
                                items = listOf("关闭", "轻", "中", "重"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Brightness4,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "壁纸暗度",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = dimIndex,
                                onSelectedIndexChange = {
                                    dimIndex = it
                                    prefsRepo.wallpaperDim = dimLevels[it]
                                }
                            )
                            OverlayDropdownPreference(
                                title = "壁纸模糊",
                                summary = "让背景柔和一点(只有图片支持)",
                                items = listOf("关闭", "轻", "中", "重"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.BlurOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "壁纸模糊",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = blurIndex,
                                onSelectedIndexChange = {
                                    blurIndex = it
                                    prefsRepo.wallpaperBlur = blurLevels[it]
                                }
                            )
                            SwitchPreference(
                                title = "界面透明",
                                summary = "卡片和背景半透明,壁纸才能透出来",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Opacity,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "界面透明",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = translucent,
                                onCheckedChange = {
                                    translucent = it
                                    prefsRepo.uiTranslucent = it
                                }
                            )
                            // ⚠️ 2026-09-16 修:Miuix 侧这两项以前被**错写进了「界面透明」的
                            //    startAction 槽**(括号少收了一层),于是它们会被画到「界面透明」
                            //    那一行的前置图标格里,而不是各自独立一行。
                            //    括号配对证据:「界面透明」的 startAction 从 L786 开到 L824(37 行),
                            //    区间里却躺着「用壁纸的主色」(L793)与「壁纸可见度」(L813)。
                            //    现在把它们提出来做平级项。
                            SwitchPreference(
                                title = "用壁纸的主色",
                                // 主色取自竖屏那张(横屏那份不参与取色,免得转个屏整套配色跳变)
                                summary = when (wallpaperKind) {
                                    "video" -> "视频壁纸暂不支持取色"
                                    "none" -> "取色只用竖屏那张图,先去上面设一张竖屏图片壁纸"
                                    else -> "从竖屏壁纸里提取主色,整套配色跟着它变"
                                },
                                startAction = {
                                    Icon(
                                        Icons.Rounded.ColorLens,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "用壁纸的主色",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = seedEnabled,
                                enabled = wallpaperKind == "image",
                                onCheckedChange = {
                                    seedEnabled = it
                                    prefsRepo.wallpaperSeed = it
                                }
                            )
                            if (translucent) {
                                SliderPreference(
                                    title = "壁纸可见度",
                                    summary = "现在是 ${(visibility * 100).roundToInt()}%   (0 = 壁纸看不见,100 = 几乎全是壁纸)",
                                    value = visibility,
                                    onValueChange = {
                                        visibility = it
                                        // 满格(100%)→ 0.02,几乎完全透明;0% → 1.0 完全不透明
                                        prefsRepo.uiTranslucentAlpha = (1f - it * 0.98f).coerceIn(0.02f, 1f)
                                    }
                                )
                            }
                            ArrowPreference(
                                title = "恢复默认背景",
                                // v0.13.161:竖屏、横屏各恢复成**各自**的内置默认图(不再"清掉横屏")
                                summary = "竖屏、横屏各用各自的内置默认图(点一下即可)",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Wallpaper,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "恢复默认背景",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    com.sevenk.core.core.utils.AppExecutors.io.execute {
                                        // ⚠️ v2.1(审计 P0-3):以前两次 applyBuiltin 的返回值**没人看**,
                                        //    写盘失败时用户点一下毫无反应(设置里还显示"已恢复默认")。
                                        //    现在把失败原因收出来,回主线程 toast。
                                        val err = com.sevenk.core.ui.util.WallpaperStore.applyBuiltin(
                                            context, com.sevenk.core.ui.util.WallpaperStore.Slot.PORTRAIT
                                        ) ?: com.sevenk.core.ui.util.WallpaperStore.applyBuiltin(
                                            // 横屏那份也设成横屏默认图(以前是 clear → 回退用竖屏那张)
                                            context, com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                                        )
                                        if (err != null) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                android.widget.Toast.makeText(
                                                    context, err, android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            )
                            if (wallpaperLandKind != "none") {
                                ArrowPreference(
                                    title = "移除横屏壁纸",
                                    summary = "横屏改回用竖屏那张,竖屏壁纸不动",
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = "移除横屏壁纸",
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        com.sevenk.core.ui.util.WallpaperStore.clear(
                                            com.sevenk.core.ui.util.WallpaperStore.Slot.LANDSCAPE
                                        )
                                    }
                                )
                            }
                            ArrowPreference(
                                title = "移除壁纸",
                                summary = "竖屏、横屏都恢复成纯色背景",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "移除壁纸",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    com.sevenk.core.ui.util.WallpaperStore.clear()
                                    prefsRepo.wallpaperDim = 0.35f
                                    prefsRepo.wallpaperBlur = 0f
                                }
                            )
                        }
                    }

                    KsuIsValid {
                        val context = LocalContext.current
                        val showHideConfirm = rememberSaveable { mutableStateOf(false) }
                        val hideConfirmDialog = rememberConfirmDialog(
                            onConfirm = {
                                showHideConfirm.value = false
                                // ⚠️ v2.1(审计 P0-2):以前 `Stealth.setEnabled(true)` 的返回值**没人看**,
                                //    开关失败(内核太旧 / 本 App 没被内核认主)时一个字都不说,
                                //    界面却照常重建 → 用户以为开了隐身,其实根本没开。
                                //    现在失败必须说出来,而且**不重建界面**(免得装成"成功"的样子)。
                                val err = Stealth.setEnabledReporting(true)
                                if (err != null) {
                                    android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    Stealth.ensureLauncherVisible(context)
                                    com.sevenk.core.ui.security.restartUiFresh(context)
                                }
                            },
                            onDismiss = { showHideConfirm.value = false }
                        )

                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            ArrowPreference(
                                title = "隐身模式",
                                summary = "界面伪装成未安装,超级用户/模块等页面全部隐藏",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.VisibilityOff,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "隐身模式",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = { showHideConfirm.value = true }
                            )
                            com.sevenk.core.ui.component.miuix.StringEditArrow(
                                title = "隐身密令",
                                value = stealthCode,
                                summary = "*#*#${stealthCode}#*#*",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Dialpad,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "隐身密令",
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onValueChange = { raw ->
                                    val code = raw.filter { it.isDigit() }.take(12)
                                    if (code.length < 4) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "密令至少 4 位数字",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else if (code != stealthCode) {
                                        stealthCode = code
                                        prefsRepo.stealthCode = code
                                        // 再写一份到 /data/adb:卸载重装后密令依然有效(否则会锁死)
                                        com.sevenk.core.core.utils.AppExecutors.io.execute {
                                            com.sevenk.core.ui.security.StealthCodeStore.write(code)
                                        }
                                    }
                                }
                            )
                            // 网页管理器 —— 服务跑在 **ksud（root 守护进程）** 里，不在 App 里。
                            // 所以划掉管理器、一键清理都不影响；重启后由 ksud 自启。
                            // App 这边只负责：开关、取地址、打开/复制地址。
                            // 🔐 v2.13：地址里带**专属密钥**（本机其它 App 再也打不到那些接口了）。
                            SwitchPreference(
                                title = waTitle,
                                summary = if (!webAdminEnabled)
                                    waSummaryOff
                                else context.getString(
                                    R.string.webadmin_summary_on,
                                    webAdminUrl.ifEmpty { waReading }
                                ),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.AdminPanelSettings,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = waTitle,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = webAdminEnabled,
                                onCheckedChange = { v ->
                                    webAdminEnabled = v
                                    prefsRepo.webAdminEnabled = v
                                    webAdminScope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            com.sevenk.core.ui.util.WebAdminCli.setEnabled(v)
                                        }
                                        if (!ok) {
                                            android.widget.Toast.makeText(
                                                context,
                                                waKsudFailed,
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        webAdminUrl = if (v) withContext(Dispatchers.IO) {
                                            com.sevenk.core.ui.util.WebAdminCli.url()
                                        } else ""
                                    }
                                }
                            )
                            if (webAdminEnabled) {
                                ArrowPreference(
                                    title = waOpen,
                                    summary = waOpenSummary,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.AdminPanelSettings,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = waOpen,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        com.sevenk.core.ui.util.WebAdminCli
                                            .openInBrowser(context, webAdminUrl)?.let { err ->
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.webadmin_browser_failed, err),
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    }
                                )
                                ArrowPreference(
                                    title = waCopy,
                                    summary = waCopySummary,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Description,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = waCopy,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        val ok = com.sevenk.core.ui.util.WebAdminCli
                                            .copyUrl(context, webAdminUrl)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (ok) waCopyOk else waCopyFail,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                                // 🔑 「忘了密钥 / 怕泄露」的补救入口：换一把，旧链接立即失效
                                ArrowPreference(
                                    title = waReset,
                                    summary = waResetSummary,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.RestartAlt,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = waReset,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        android.app.AlertDialog.Builder(context)
                                            .setTitle(waReset)
                                            .setMessage(waResetConfirm)
                                            .setPositiveButton(context.getString(R.string.confirm)) { _, _ ->
                                                webAdminScope.launch {
                                                    val newUrl = withContext(Dispatchers.IO) {
                                                        com.sevenk.core.ui.util.WebAdminCli.resetToken()
                                                    }
                                                    if (newUrl.isNotEmpty()) {
                                                        webAdminUrl = newUrl
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            context.getString(R.string.webadmin_reset_ok, newUrl),
                                                            android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            waResetFailed,
                                                            android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            }
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .show()
                                    }
                                )
                                ArrowPreference(
                                    title = waDiagnose,
                                    summary = waDiagnoseSummary,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.BugReport,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = waDiagnose,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        webAdminScope.launch {
                                            val report = withContext(Dispatchers.IO) {
                                                com.sevenk.core.ui.util.WebAdminCli.diagnose()
                                            }
                                            android.app.AlertDialog.Builder(context)
                                                .setTitle(waDiagnoseTitle)
                                                .setMessage(report)
                                                .setPositiveButton(waCopyAll) { _, _ ->
                                                    com.sevenk.core.ui.util.WebAdminCli
                                                        .copyUrl(context, report)
                                                    android.widget.Toast.makeText(
                                                        context, waCopyAllOk,
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                .setNegativeButton(android.R.string.cancel, null)
                                                .show()
                                        }
                                    }
                                )
                            }
                        }

                        if (showHideConfirm.value) {
                            hideConfirmDialog.showConfirm(
                                title = "开启隐身模式?",
                                content = "开启后本管理器会显示为「未安装」,超级用户/模块等页面全部隐藏。\n\n桌面图标保持可见(不会被隐藏,点图标即可进入)。\n\n关闭方式:拨号盘输入 *#*#${stealthCode}#*#*",
                                confirm = "开启"
                            )
                        }
                    }

                    KsuIsValid {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val profileTemplate = stringResource(id = R.string.settings_profile_template)
                            ArrowPreference(
                                title = profileTemplate,
                                summary = stringResource(id = R.string.settings_profile_template_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Description,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = profileTemplate,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = actions.onOpenProfileTemplate
                            )
                        }
                    }

                    KsuIsValid {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val suCompatModeItems = listOf(
                                stringResource(id = R.string.settings_mode_enable_by_default),
                                stringResource(id = R.string.settings_mode_disable_until_reboot),
                                stringResource(id = R.string.settings_mode_disable_always),
                            )

                            val suSummary = when (uiState.suCompatStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sucompat_summary)
                            }
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.settings_sucompat),
                                summary = suSummary,
                                items = suCompatModeItems,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.AdminPanelSettings,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sucompat),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onSelectedIndexChange = actions.onSetSuCompatMode
                            )

                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_kernel_umount),
                                summary = umountSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.LayersClear,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_kernel_umount),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.kernelUmountStatus == "supported",
                                checked = uiState.isKernelUmountEnabled,
                                onCheckedChange = actions.onSetKernelUmountEnabled
                            )

                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_selinux_hide),
                                summary = selinuxHideSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Security,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_selinux_hide),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.selinuxHideStatus == "supported",
                                checked = uiState.isSelinuxHideEnabled,
                                onCheckedChange = actions.onSetSelinuxHideEnabled
                            )

                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_sulog),
                                summary = sulogSummary,
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Article,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sulog),
                                        tint = if (uiState.sulogStatus == "supported") colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.sulogStatus == "supported",
                                checked = uiState.isSulogEnabled,
                                onCheckedChange = actions.onSetSulogEnabled
                            )

                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_adb_root),
                                summary = adbRootSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Adb,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_adb_root),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.adbRootStatus == "supported",
                                checked = uiState.isAdbRootEnabled,
                                onCheckedChange = actions.onSetAdbRootEnabled
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_soft_reboot),
                                summary = stringResource(id = R.string.settings_soft_reboot_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_soft_reboot),
                                        tint = if (uiState.isLateLoadMode) colorScheme.disabledOnSecondaryVariant else colorScheme.onBackground
                                    )
                                },
                                enabled = !uiState.isLateLoadMode,
                                checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                onCheckedChange = actions.onSetUseSoftReboot
                            )
                        }

                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Rule,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_umount_modules_default),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.isDefaultUmountModules,
                                onCheckedChange = actions.onSetDefaultUmountModules
                            )

                            SwitchPreference(
                                title = stringResource(id = R.string.enable_web_debugging),
                                summary = stringResource(id = R.string.enable_web_debugging_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeveloperMode,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.enable_web_debugging),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableWebDebugging,
                                onCheckedChange = actions.onSetEnableWebDebugging
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.FlashOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_auto_jailbreak),
                                        tint = if (uiState.isLateLoadMode) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.isLateLoadMode,
                                checked = uiState.autoJailbreak,
                                onCheckedChange = actions.onSetAutoJailbreak
                            )
                        }
                    }

                    if (uiState.isLkmMode) {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val uninstall = stringResource(id = R.string.settings_uninstall)
                            ArrowPreference(
                                title = uninstall,
                                enabled = !uiState.isLateLoadMode,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = uninstall,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUninstallDialog.value = true },
                            )
                            UninstallDialog(
                                show = showUninstallDialog.value,
                                onDismissRequest = { showUninstallDialog.value = false }
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.send_log),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.send_log),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = stringResource(id = R.string.about)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

/** 导航图标设置行右侧的小预览:显示当前选好的那张图(圆形/着色与导航栏一致) */
@Composable
private fun NavIconPreview(key: String) {
    val context = LocalContext.current
    val rev = com.sevenk.core.ui.util.WallpaperPrefs.observe()
    val circle = remember(rev) {
        com.sevenk.core.data.repository.SettingsRepositoryImpl().navIconCircle
    }
    val bitmap by produceState<ImageBitmap?>(null, context, key, NavIcons.version) {
        value = withContext(Dispatchers.IO) { NavIcons.load(context, key, 96) }
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier
            .size(28.dp)
            .then(if (circle) Modifier.clip(CircleShape) else Modifier),
        contentScale = ContentScale.Crop,
    )
}

/**
 * 「自裁取景」弹窗:把卡片比例的一块区域当取景框,拖动/双指缩放调整,
 * 预览用的是首页同一份渲染(StatusDecorationImage),所以所见即所得。
 */
@Composable
private fun DecorationCropDialog(
    onDismiss: () -> Unit,
    onApply: (biasX: Float, biasY: Float, zoom: Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, context, StatusDecoration.version, screenWidthPx) {
        value = withContext(Dispatchers.IO) { StatusDecoration.load(context, screenWidthPx) }
    }
    val repo = remember { com.sevenk.core.data.repository.SettingsRepositoryImpl() }
    var biasX by remember { mutableFloatStateOf(repo.statusDecorationBiasX) }
    var biasY by remember { mutableFloatStateOf(repo.statusDecorationBiasY) }
    var zoom by remember { mutableFloatStateOf(repo.statusDecorationZoom) }
    // 卡片 = (屏宽 − 左右各 12dp) × CARD_HEIGHT_DP,这里按同一比例开取景框。
    // 必须和首页的 sideInset 用同一个常量,否则取景框比例对不上、就不是所见即所得了。
    val cardAspect = (config.screenWidthDp - 2 * StatusDecoration.CARD_SIDE_INSET_DP) /
        StatusDecoration.CARD_HEIGHT_DP

    WindowDialog(
        show = true,
        backgroundColor = miuixDialogColor(),
        title = "自裁取景",
        // 同 ModuleIconDialog:不传 onDismissRequest 的话,返回键和点击外部都不响应,
        // 万一底下那排按钮不可见就彻底退不出来了。这里按"取消"处理。
        onDismissRequest = onDismiss,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(cardAspect)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                // 手指往哪边拖,就露出图片哪边(所以偏移是减)
                                biasX = (biasX - pan.x / w * 2f).coerceIn(-1f, 1f)
                                biasY = (biasY - pan.y / h * 2f).coerceIn(-1f, 1f)
                                zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            }
                        },
                ) {
                    val image = bitmap
                    if (image != null) {
                        com.sevenk.core.ui.util.StatusDecorationImage(
                            bitmap = image,
                            biasX = biasX,
                            biasY = biasY,
                            zoom = zoom,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Text(
                    text = "拖动调位置 · 双指缩放 · 现在是 ${"%.1f".format(zoom)}×",
                    fontSize = 12.sp,
                    color = colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 显式缩放按钮:平板/单指场景比捏合好使
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "缩小",
                        onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                        modifier = Modifier.weight(1f),
                        enabled = zoom > 1f,
                    )
                    TextButton(
                        text = "放大",
                        onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                        modifier = Modifier.weight(1f),
                        enabled = zoom < 4f,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "复位",
                        onClick = {
                            biasX = 0f
                            biasY = 0f
                            zoom = 1f
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "确定",
                        onClick = { onApply(biasX, biasY, zoom) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
