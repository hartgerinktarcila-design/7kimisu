package com.sevenk.core.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sevenk.core.R
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.component.KsuIsValid
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.material.ExpressiveScaffold
import com.sevenk.core.ui.component.material.SegmentedColumn
import com.sevenk.core.ui.component.material.SegmentedDropdownItem
import com.sevenk.core.ui.component.material.SegmentedListItem
import com.sevenk.core.ui.component.material.SegmentedSliderItem
import com.sevenk.core.ui.component.material.SegmentedStringItem
import com.sevenk.core.ui.component.material.SegmentedSwitchItem
import com.sevenk.core.ui.component.material.SendLogBottomSheet
import com.sevenk.core.ui.component.material.SnackBarHost
import com.sevenk.core.ui.component.material.expressiveTopAppBarColors
import com.sevenk.core.ui.component.uninstalldialog.UninstallDialog
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.security.findActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SevereCold
import androidx.compose.material.icons.filled.ViewInAr
// 美化类第二批(2026-09-16)新增用到的图标
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.ZoomIn
import com.sevenk.core.ui.component.material.DecorationCropDialog
import com.sevenk.core.ui.component.material.NavIconPreview
import com.sevenk.core.ui.util.NavIcons
import com.sevenk.core.ui.util.AnnouncementPrefs
import com.sevenk.core.ui.util.StatusDecoration
import com.sevenk.core.ui.util.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMaterial(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackBarHost = remember { SnackbarHostState() }
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
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
    var showBottomSheet by remember { mutableStateOf(false) }

    // 隐身密令(拨号数字)。
    // ⚠️ 必须显示「真实生效」的那一份,**不能写死 70707**:
    //    用户可以自定义密令,而且自定义值会备份到 /data/adb/sevenk/stealth_code(跨卸载不丢),
    //    此时真正生效的就不是 70707 了。
    //    写死的后果(2026-09-16 有真实用户踩到):确认框提示他拨 70707 → 与真实密令不匹配
    //    → StealthReceiver 静默忽略 → 用户看到的是"拨了一点反应都没有" → 被关在隐身里出不来。
    //    这里与 Miuix 设置页(SettingsMiuix)保持同一套取值逻辑。
    val prefsRepo = remember { SettingsRepositoryImpl() }
    var stealthCode by remember { mutableStateOf(prefsRepo.stealthCode) }
    LaunchedEffect(Unit) {
        val real = withContext(Dispatchers.IO) {
            com.sevenk.core.ui.security.StealthCodeStore.sync()
            com.sevenk.core.ui.security.StealthCodeStore.effectiveCode(context)
        }
        if (real.isNotBlank() && real != stealthCode) stealthCode = real
    }

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
                //    改成"用户真进设置页 / 真打开这个开关"时再同步 —— 语义不变。
                com.sevenk.core.ui.util.WebAdminCli.syncPref(true)
                com.sevenk.core.ui.util.WebAdminCli.url()
            }
        } else {
            ""
        }
    }

    // 网页管理器的界面文案（4 语言）。⚠️ 必须在**组合期**取好：
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

    // ── 个性化(2026-09-16 补齐)──
    // 这一段以前在 Material 侧**完全缺失**(审计:Material 19 项 vs Miuix 48 项)。
    // 配置键与默认值都与 Miuix 设置页完全一致 → 两边改的是同一份 prefs,
    // 所以来回切换界面风格不会丢设置。
    var flatHome by remember { mutableStateOf(prefsRepo.flatHome) }
    // v2.26「不再显示公告」：与 Miuix 设置页**同一个 pref 键、同一个默认值（关）**，
    // 键只在 `ui/util/AnnouncementPrefs.kt` 定义一处 —— 两边改的是同一份设置。
    var announcementNeverShow by remember { mutableStateOf(AnnouncementPrefs.neverShow(context)) }
    var snowEnabled by remember { mutableStateOf(prefsRepo.snowEnabled) }
    var snowSize by remember { mutableStateOf(prefsRepo.snowSize) }
    var snowSpeed by remember { mutableStateOf(prefsRepo.snowSpeed) }
    var trollRainEnabled by remember { mutableStateOf(prefsRepo.trollRainEnabled) }
    var trollRainSize by remember { mutableStateOf(prefsRepo.trollRainSize) }
    var trollRainSpeed by remember { mutableStateOf(prefsRepo.trollRainSpeed) }
    var gravityEnabled by remember { mutableStateOf(prefsRepo.gravityEnabled) }
    var gravityMode by remember { mutableStateOf(prefsRepo.gravityMode) }

    // ── 美化类(2026-09-16 第二批)──
    // 与 Miuix 设置页一一对应:同一批配置键、同一套换算公式,所以来回切风格不丢设置。
    // 自定义状态卡图片的取景/缩放/压暗/只留图片
    val decorDimLevels = remember { listOf(0f, 0.3f, 0.55f, 0.8f) }
    var decorAnchor by remember { mutableStateOf(prefsRepo.statusDecorationAnchor) }
    var decorDimIndex by remember {
        mutableStateOf(
            decorDimLevels.indexOfFirst { kotlin.math.abs(it - prefsRepo.statusDecorationDim) < 0.02f }
                .takeIf { it >= 0 } ?: 2
        )
    }
    var decorHideText by remember { mutableStateOf(prefsRepo.statusDecorationHideText) }
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
    // 壁纸
    val wallVersion = WallpaperStore.version
    // 竖屏那份 = 老的那份;横屏那份是新加的第二份,没设时横屏回退用竖屏那张
    val wallpaperKind = WallpaperStore.kind(WallpaperStore.Slot.PORTRAIT)
    val wallpaperLandKind = WallpaperStore.kind(WallpaperStore.Slot.LANDSCAPE)
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
    // 壁纸可见度 0~1:1 = 界面几乎全透明(壁纸完全露出来),0 = 界面不透明
    var visibility by remember {
        mutableStateOf(((1f - prefsRepo.uiTranslucentAlpha) / 0.98f).coerceIn(0f, 1f))
    }
    val wallpaperInfo = remember(wallVersion, prefsRepo) {
        val f = WallpaperStore.file(context, WallpaperStore.Slot.PORTRAIT)
        if (f == null) "" else "  ${f.length() / 1024}KB"
    }
    val wallpaperLandInfo = remember(wallVersion, prefsRepo) {
        val f = WallpaperStore.file(context, WallpaperStore.Slot.LANDSCAPE)
        if (f == null) "" else "  ${f.length() / 1024}KB"
    }
    // 壁纸选取器:竖屏/横屏共用两个 launcher,靠 pendingWallSlot 记住"这次是给哪一份选的"
    var pendingWallSlot by remember { mutableStateOf(WallpaperStore.Slot.PORTRAIT) }
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
                val err = WallpaperStore.save(context, uri, isVideo = false, slot = slot)
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
                val err = WallpaperStore.save(context, uri, isVideo = true, slot = slot)
                if (err != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    UninstallDialog(
        show = showUninstallDialog.value,
        onDismissRequest = { showUninstallDialog.value = false }
    )

    ExpressiveScaffold(
        topBar = {
            TopBar(scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.padding(bottom = bottomInnerPadding)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            KsuIsValid {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.SystemUpdate,
                                title = stringResource(id = R.string.settings_check_update),
                                summary = stringResource(id = R.string.settings_check_update_summary),
                                checked = uiState.checkUpdate,
                                onCheckedChange = actions.onSetCheckUpdate
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.SystemUpdateAlt,
                                title = stringResource(id = R.string.settings_module_check_update),
                                summary = stringResource(id = R.string.settings_check_update_summary),
                                checked = uiState.checkModuleUpdate,
                                onCheckedChange = actions.onSetCheckModuleUpdate
                            )
                        }
                    )
                )
            }

            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add {
                        SegmentedDropdownItem(
                            icon = Icons.Filled.DisplaySettings,
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onItemSelected = actions.onSetUiModeIndex
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onOpenTheme,
                            headlineContent = { Text(stringResource(id = R.string.settings_theme)) },
                            supportingContent = { Text(stringResource(id = R.string.settings_theme_summary)) },
                            leadingContent = { Icon(Icons.Filled.Palette, stringResource(id = R.string.settings_theme)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = { pickDecoration.launch("image/*") },
                            headlineContent = { Text("状态卡图片") },
                            supportingContent = {
                                Text(if (hasDecoration) "已自定义(点一下换一张)" else "换掉首页那个绿色对勾")
                            },
                            leadingContent = { Icon(Icons.Filled.Image, "状态卡图片") },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        )
                    }
                    if (hasDecoration) {
                        add {
                            SegmentedListItem(
                                onClick = {
                                    StatusDecoration.clear(context)
                                    hasDecoration = StatusDecoration.hasCustom(context)
                                },
                                headlineContent = { Text("恢复默认图片") },
                                supportingContent = { Text("用回自带的绿色对勾") },
                                leadingContent = { Icon(Icons.Filled.Restore, "恢复默认图片") },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            )
                        }
                        // 取景 / 缩放 / 压暗 / 只留图片 —— 键名与换算和 Miuix 设置页完全一致
                        add {
                            SegmentedDropdownItem(
                                icon = Icons.Filled.CropFree,
                                title = "图片取景",
                                summary = "平板壁纸比卡片高,选看哪一条;选「自裁」可以自己拖",
                                items = listOf("居中", "偏上(露天空/头发)", "偏下(露地面/水面)", "自裁(自己拖/缩放)"),
                                selectedIndex = decorAnchor.coerceIn(0, 3),
                                onItemSelected = { idx ->
                                    if (idx == 3) {
                                        // 自裁:先开弹窗调,确定后才落盘(decorAnchor 在那时置 3)
                                        showCropDialog = true
                                    } else {
                                        decorAnchor = idx
                                        prefsRepo.statusDecorationAnchor = idx
                                    }
                                }
                            )
                        }
                        add {
                            SegmentedSliderItem(
                                icon = Icons.Filled.ZoomIn,
                                title = "图片缩放",
                                summary = "现在 ${"%.1f".format(decorZoom)}×（往右更大;自裁弹窗里也能双指缩放）",
                                value = ((decorZoom - 1f) / 3f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val z = 1f + it * 3f
                                    decorZoom = z
                                    prefsRepo.statusDecorationZoom = z
                                }
                            )
                        }
                        add {
                            SegmentedDropdownItem(
                                icon = Icons.Filled.Brightness4,
                                title = "图片压暗",
                                summary = "压暗一点,上面的字更清楚(关掉则靠文字描边)",
                                items = listOf("关闭", "轻", "中", "重"),
                                selectedIndex = decorDimIndex,
                                onItemSelected = {
                                    decorDimIndex = it
                                    prefsRepo.statusDecorationDim = decorDimLevels[it]
                                }
                            )
                        }
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.TextFields,
                                title = "只留图片",
                                summary = "不显示标题/版本/LKM,整张卡只有图",
                                checked = decorHideText,
                                onCheckedChange = {
                                    decorHideText = it
                                    prefsRepo.statusDecorationHideText = it
                                }
                            )
                        }
                    }

                    // ── 导航图标自定义 ───────────────────────────────
                    // 说明:GitHub 上那些好看的二次元图标大多是画师作品、没有可再分发的开源许可,
                    // 所以我们不内置,而是把选择权交给用户 —— 你想用哪个角色都行。
                    // ⚠️ 这里是 buildList(普通函数),**不能调 remember**;但 navIconVersion 是
                    //    snapshot state,直接读一下就会订阅 —— 保存/清除图标后整段会重建。
                    val navIconRev = navIconVersion
                    val navIconLabels = listOf("主页", "超级用户", "模块", "设置")
                    val navIconPreviews = listOf(
                        Icons.Filled.Cottage,
                        Icons.Filled.Security,
                        Icons.Filled.Extension,
                        Icons.Filled.Settings,
                    )
                    navIconLabels.forEachIndexed { idx, name ->
                        val key = NavIcons.keys[idx]
                        add {
                            val has = remember(navIconRev) { NavIcons.has(context, key) }
                            SegmentedListItem(
                                onClick = {
                                    pendingNavKey = key
                                    pickNavIcon.launch("image/*")
                                },
                                headlineContent = { Text("导航图标 · $name") },
                                supportingContent = {
                                    Text(if (has) "已自定义(点一下换一张)" else "当前是默认图标(点一下选图)")
                                },
                                leadingContent = { Icon(navIconPreviews[idx], "导航图标 · $name") },
                                // 有自定义图就显示小预览(圆形/着色与导航栏一致),否则只剩箭头
                                trailingContent = if (has) {
                                    { NavIconPreview(key) }
                                } else {
                                    { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                                },
                            )
                        }
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Circle,
                            title = "导航图标裁成圆形",
                            summary = "头像/人物图开这个最好看",
                            checked = navIconCircle,
                            onCheckedChange = {
                                navIconCircle = it
                                prefsRepo.navIconCircle = it
                            }
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Palette,
                            title = "导航图标着色",
                            summary = "按主题色染成单色(适合剪影图;彩色美少女图建议关闭)",
                            checked = navIconTint,
                            onCheckedChange = {
                                navIconTint = it
                                prefsRepo.navIconTint = it
                            }
                        )
                    }
                    if (NavIcons.hasAny(context)) {
                        add {
                            SegmentedListItem(
                                onClick = {
                                    NavIcons.clearAll(context)
                                    navIconVersion = NavIcons.version
                                },
                                headlineContent = { Text("恢复默认导航图标") },
                                supportingContent = { Text("四个图标全部换回自带的") },
                                leadingContent = { Icon(Icons.Filled.Restore, "恢复默认导航图标") },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            )
                        }
                    }
                }
            )

            // ── 壁纸(美化类第二批,2026-09-16 补齐)──
            // 与 Miuix 设置页同一批配置键:wallpaper_* / ui_translucent* ,来回切风格不丢设置。
            // ⚠️「界面透明」会把主题 background 的 alpha 压到 0.02 —— 所以本页所有弹窗
            //    (含上面的自裁取景)都必须显式传 materialDialogColor(),否则会变成全透明。
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add {
                        // ⚠️ v0.13.160:分朝向。原来这一项(「背景壁纸」)改叫「竖屏壁纸」,
                        //    用的还是老的那份设置(老用户数据不动);横屏是新加的第二份,
                        //    没单独设时横屏自动沿用竖屏这张 —— 升级后观感不变。
                        SegmentedListItem(
                            onClick = {
                                pendingWallSlot = WallpaperStore.Slot.PORTRAIT
                                pickWallpaperImage.launch("image/*")
                            },
                            headlineContent = { Text("竖屏壁纸") },
                            supportingContent = {
                                Text(
                                    when {
                                        WallpaperStore.looksBroken(WallpaperStore.Slot.PORTRAIT) ->
                                            "⚠️ 壁纸文件已丢失,请重新选一张(点一下)"
                                        wallpaperKind == "image" -> "当前:图片$wallpaperInfo(点一下换一张)"
                                        wallpaperKind == "video" -> "当前:视频$wallpaperInfo(点一下换成图片)"
                                        else -> "选一张图当竖屏背景(横屏没单独设时也用它)"
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Wallpaper, "竖屏壁纸") },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = {
                                pendingWallSlot = WallpaperStore.Slot.PORTRAIT
                                pickWallpaperVideo.launch("video/*")
                            },
                            headlineContent = { Text("竖屏视频") },
                            supportingContent = {
                                Text(
                                    if (wallpaperKind == "video") "当前:视频(点一下换一段)"
                                    else "选一段视频当竖屏背景(静音循环播放)"
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Movie, "竖屏视频") },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    }
                    // ── 横屏壁纸(v0.13.160 新增)──
                    add {
                        SegmentedListItem(
                            onClick = {
                                pendingWallSlot = WallpaperStore.Slot.LANDSCAPE
                                pickWallpaperImage.launch("image/*")
                            },
                            headlineContent = { Text("横屏壁纸") },
                            supportingContent = {
                                Text(
                                    when {
                                        WallpaperStore.looksBroken(WallpaperStore.Slot.LANDSCAPE) ->
                                            "⚠️ 横屏壁纸文件已丢失,请重新选一张(点一下)"
                                        wallpaperLandKind == "image" -> "当前:图片$wallpaperLandInfo(点一下换一张)"
                                        wallpaperLandKind == "video" -> "当前:视频$wallpaperLandInfo(点一下换成图片)"
                                        else -> "不设就横屏沿用竖屏那张(点一下选图)"
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.ScreenRotation, "横屏壁纸") },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = {
                                pendingWallSlot = WallpaperStore.Slot.LANDSCAPE
                                pickWallpaperVideo.launch("video/*")
                            },
                            headlineContent = { Text("横屏视频") },
                            supportingContent = {
                                Text(
                                    if (wallpaperLandKind == "video") "当前:视频(点一下换一段)"
                                    else "选一段视频当横屏背景(静音循环播放)"
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Movie, "横屏视频") },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    }
                    if (wallpaperKind != "none" || wallpaperLandKind != "none") {
                        add {
                            SegmentedDropdownItem(
                                icon = Icons.Filled.Brightness4,
                                title = "壁纸暗度",
                                summary = "压暗一点,上面的字更清楚",
                                items = listOf("关闭", "轻", "中", "重"),
                                selectedIndex = dimIndex,
                                onItemSelected = {
                                    dimIndex = it
                                    prefsRepo.wallpaperDim = dimLevels[it]
                                }
                            )
                        }
                        add {
                            SegmentedDropdownItem(
                                icon = Icons.Filled.BlurOn,
                                title = "壁纸模糊",
                                summary = "让背景柔和一点(只有图片支持)",
                                items = listOf("关闭", "轻", "中", "重"),
                                selectedIndex = blurIndex,
                                onItemSelected = {
                                    blurIndex = it
                                    prefsRepo.wallpaperBlur = blurLevels[it]
                                }
                            )
                        }
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Opacity,
                                title = "界面透明",
                                summary = "卡片和背景半透明,壁纸才能透出来",
                                checked = translucent,
                                onCheckedChange = {
                                    translucent = it
                                    prefsRepo.uiTranslucent = it
                                }
                            )
                        }
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.ColorLens,
                                title = "用壁纸的主色",
                                // 主色取自竖屏那张(横屏那份不参与取色,免得转个屏整套配色跳变)
                                summary = when (wallpaperKind) {
                                    "video" -> "视频壁纸暂不支持取色"
                                    "none" -> "取色只用竖屏那张图,先去上面设一张竖屏图片壁纸"
                                    else -> "从竖屏壁纸里提取主色,整套配色跟着它变"
                                },
                                checked = seedEnabled,
                                enabled = wallpaperKind == "image",
                                onCheckedChange = {
                                    seedEnabled = it
                                    prefsRepo.wallpaperSeed = it
                                }
                            )
                        }
                        if (translucent) {
                            add {
                                SegmentedSliderItem(
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
                        }
                        add {
                            SegmentedListItem(
                                onClick = {
                                    com.sevenk.core.core.utils.AppExecutors.io.execute {
                                        // v0.13.161:竖屏、横屏各设成**各自**的内置默认图
                                        // (以前是"竖屏设默认图 + 清掉横屏那份" → 横屏回退用竖屏那张)
                                        // ⚠️ v2.1(审计 P0-3):返回值以前没人看,失败时用户点一下毫无反应。
                                        //    现在把失败原因收出来,回主线程 toast。
                                        val err = WallpaperStore.applyBuiltin(context, WallpaperStore.Slot.PORTRAIT)
                                            ?: WallpaperStore.applyBuiltin(context, WallpaperStore.Slot.LANDSCAPE)
                                        if (err != null) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                android.widget.Toast.makeText(
                                                    context, err, android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                headlineContent = { Text("恢复默认背景") },
                                supportingContent = { Text("竖屏、横屏各用各自的内置默认图(点一下即可)") },
                                leadingContent = { Icon(Icons.Filled.Wallpaper, "恢复默认背景") },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                },
                            )
                        }
                        if (wallpaperLandKind != "none") {
                            add {
                                SegmentedListItem(
                                    onClick = { WallpaperStore.clear(WallpaperStore.Slot.LANDSCAPE) },
                                    headlineContent = { Text("移除横屏壁纸") },
                                    supportingContent = { Text("横屏改回用竖屏那张,竖屏壁纸不动") },
                                    leadingContent = { Icon(Icons.Filled.Delete, "移除横屏壁纸") },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                    },
                                )
                            }
                        }
                        add {
                            SegmentedListItem(
                                onClick = {
                                    WallpaperStore.clear()
                                    prefsRepo.wallpaperDim = 0.35f
                                    prefsRepo.wallpaperBlur = 0f
                                },
                                headlineContent = { Text("移除壁纸") },
                                supportingContent = { Text("竖屏、横屏都恢复成纯色背景") },
                                leadingContent = { Icon(Icons.Filled.Delete, "移除壁纸") },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                },
                            )
                        }
                    }
                }
            )

            // 「自裁取景」弹窗:拖动调位置、双指缩放,确定后落盘
            if (showCropDialog) {
                DecorationCropDialog(
                    onDismiss = { showCropDialog = false },
                    onApply = { bx, by, zm ->
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

            // ── 个性化 —— 2026-09-16 补齐 ──
            // 这一整段以前在 Material 侧是**完全缺失**的(审计:Material 19 项 vs Miuix 48 项),
            // 用户切到 Material 就会觉得"一堆开关凭空消失"。
            // 配置键 / 默认值 / 滑杆换算都与 Miuix 设置页一一对应,改的是同一份 prefs。
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.LayersClear,
                            title = "界面扁平化",
                            summary = "顶栏/底栏/信息卡片都只留文字和图标,不画底色",
                            checked = flatHome,
                            onCheckedChange = {
                                flatHome = it
                                prefsRepo.flatHome = it
                            }
                        )
                    }
                    // 🔴 v2.26（用户点名要的开关）：打开后开屏公告**永远不再弹**。
                    //    关着时公告也**不再每次冷启动都弹** —— 只在"有新公告"时弹一次
                    //    （判据见 ui/util/AnnouncementPrefs.kt 与 AnnouncementPopup.kt）。
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Campaign,
                            title = stringResource(id = R.string.settings_announcement_never_show),
                            summary = stringResource(id = R.string.settings_announcement_never_show_summary),
                            checked = announcementNeverShow,
                            onCheckedChange = {
                                announcementNeverShow = it
                                AnnouncementPrefs.setNeverShow(context, it)
                            }
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.SevereCold,
                            title = "下雪效果",
                            summary = "整个界面上下大雪(氛围特效,可随时关掉)",
                            checked = snowEnabled,
                            onCheckedChange = {
                                snowEnabled = it
                                prefsRepo.snowEnabled = it
                            }
                        )
                    }
                    if (snowEnabled) {
                        add {
                            SegmentedSliderItem(
                                title = "雪花大小",
                                summary = "现在 ${"%.1f".format(snowSize)}×（往右更大）",
                                value = ((snowSize - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    snowSize = s
                                    prefsRepo.snowSize = s
                                }
                            )
                        }
                        add {
                            SegmentedSliderItem(
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
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.ViewInAr,
                            title = "巨魔雨",
                            summary = "蓝巨魔图标翻滚着下雨",
                            checked = trollRainEnabled,
                            onCheckedChange = {
                                trollRainEnabled = it
                                prefsRepo.trollRainEnabled = it
                            }
                        )
                    }
                    if (trollRainEnabled) {
                        add {
                            SegmentedSliderItem(
                                title = "巨魔大小",
                                summary = "现在 ${"%.1f".format(trollRainSize)}×（往右更大）",
                                value = ((trollRainSize - 0.4f) / 2.6f).coerceIn(0f, 1f),
                                onValueChange = {
                                    val s = 0.4f + it * 2.6f
                                    trollRainSize = s
                                    prefsRepo.trollRainSize = s
                                }
                            )
                        }
                        add {
                            SegmentedSliderItem(
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
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.ScreenRotation,
                            title = "重力感应",
                            summary = "倾斜手机,雪花和巨魔会朝低的一侧加速流下",
                            checked = gravityEnabled,
                            onCheckedChange = {
                                gravityEnabled = it
                                prefsRepo.gravityEnabled = it
                            }
                        )
                    }
                    if (gravityEnabled) {
                        add {
                            SegmentedDropdownItem(
                                icon = Icons.Filled.ScreenRotation,
                                title = "重力方向校准",
                                summary = "倾斜方向反了就换一个（一般用不到）",
                                items = listOf("正常", "左右翻转", "上下翻转", "反转 180°"),
                                selectedIndex = gravityMode.coerceIn(0, 3),
                                onItemSelected = {
                                    gravityMode = it
                                    prefsRepo.gravityMode = it
                                }
                            )
                        }
                    }
                    // ⚠️ v0.13.157:原来这里有一条「隐身期间个性化不生效(素颜)」的提示,已删除
                    //    —— 隐身不再影响任何个性化(壁纸/特效/配色/扁平/自定义图片照常生效,
                    //    见 security/Stealth.kt 顶部注释),所以这句提示本身已经不成立。
                }
            )

            KsuIsValid {
                val context = LocalContext.current
                val showHideConfirm = rememberSaveable { mutableStateOf(false) }
                val hideConfirmDialog = rememberConfirmDialog(
                    onConfirm = {
                        showHideConfirm.value = false
                        // ⚠️ v2.1(审计 P0-2):开关失败不能静默。失败时**不重建界面**,
                        //    免得界面装成"隐身已开"的样子骗用户。
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

                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = buildList {
                        add {
                        SegmentedListItem(
                            onClick = { showHideConfirm.value = true },
                            headlineContent = { Text("隐身模式") },
                            supportingContent = { Text("界面伪装成未安装,超级用户/模块等页面全部隐藏") },
                            leadingContent = { Icon(Icons.Filled.VisibilityOff, "隐身模式") },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                        }
                        // 隐身密令 —— Material 侧以前**根本没有这一项**(只有 Miuix 有),
                        // 用户切到 Material 就改不了密令。2026-09-16 审计发现,本版补上。
                        // 逻辑与 Miuix 完全一致:只留数字、最多 12 位、至少 4 位,
                        // 并且同时写一份到 /data/adb/sevenk/stealth_code(卸载重装不丢)。
                        add {
                            SegmentedStringItem(
                                icon = Icons.Filled.Dialpad,
                                title = "隐身密令",
                                value = stealthCode,
                                summary = "*#*#${stealthCode}#*#*",
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
                        }
                        // 网页管理器 —— 服务跑在 ksud（root 守护进程）里，不在 App 里
                        // 🔐 v2.13：地址里带**专属密钥**（本机其它 App 再也打不到那些接口了）。
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = waTitle,
                                summary = if (!webAdminEnabled)
                                    waSummaryOff
                                else context.getString(
                                    R.string.webadmin_summary_on,
                                    webAdminUrl.ifEmpty { waReading }
                                ),
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
                        }
                        if (webAdminEnabled) {
                            add {
                                SegmentedListItem(
                                    onClick = {
                                        com.sevenk.core.ui.util.WebAdminCli
                                            .openInBrowser(context, webAdminUrl)?.let { err ->
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.webadmin_browser_failed, err),
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    },
                                    headlineContent = { Text(waOpen) },
                                    supportingContent = { Text(waOpenSummary) },
                                    leadingContent = { Icon(Icons.Filled.AdminPanelSettings, waOpen) }
                                )
                            }
                            add {
                                SegmentedListItem(
                                    onClick = {
                                        val ok = com.sevenk.core.ui.util.WebAdminCli
                                            .copyUrl(context, webAdminUrl)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (ok) waCopyOk else waCopyFail,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    headlineContent = { Text(waCopy) },
                                    supportingContent = { Text(waCopySummary) },
                                    leadingContent = { Icon(Icons.Filled.Description, waCopy) }
                                )
                            }
                            // 🔑 「忘了密钥 / 怕泄露」的补救入口：换一把，旧链接立即失效
                            add {
                                SegmentedListItem(
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
                                    },
                                    headlineContent = { Text(waReset) },
                                    supportingContent = { Text(waResetSummary) },
                                    leadingContent = { Icon(Icons.Filled.RestartAlt, waReset) }
                                )
                            }
                            add {
                                SegmentedListItem(
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
                                    },
                                    headlineContent = { Text(waDiagnose) },
                                    supportingContent = { Text(waDiagnoseSummary) },
                                    leadingContent = { Icon(Icons.Filled.BugReport, waDiagnose) }
                                )
                            }
                        }
                    }
                )

                if (showHideConfirm.value) {
                    hideConfirmDialog.showConfirm(
                        title = "开启隐身模式?",
                        content = "开启后本管理器会显示为「未安装」,超级用户/模块等页面全部隐藏。\n\n桌面图标保持可见(不会被隐藏,点图标即可进入)。\n\n关闭方式:拨号盘输入 *#*#${stealthCode}#*#*",
                        confirm = "开启"
                    )
                }
            }

            val profileTemplate = stringResource(id = R.string.settings_profile_template)
            KsuIsValid {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = actions.onOpenProfileTemplate,
                            headlineContent = { Text(profileTemplate) },
                            supportingContent = { Text(stringResource(id = R.string.settings_profile_template_summary)) },
                            leadingContent = { Icon(Icons.Filled.Description, profileTemplate) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                )
            }

            KsuIsValid {
                val suCompatModeItems = listOf(
                    stringResource(id = R.string.settings_mode_enable_by_default),
                    stringResource(id = R.string.settings_mode_disable_until_reboot),
                    stringResource(id = R.string.settings_mode_disable_always),
                )

                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            val suSummary = when (uiState.suCompatStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sucompat_summary)
                            }
                            SegmentedDropdownItem(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = stringResource(id = R.string.settings_sucompat),
                                summary = suSummary,
                                items = suCompatModeItems,
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onItemSelected = actions.onSetSuCompatMode
                            )
                        },
                        {
                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.LayersClear,
                                title = stringResource(id = R.string.settings_kernel_umount),
                                summary = umountSummary,
                                enabled = uiState.kernelUmountStatus == "supported",
                                checked = uiState.isKernelUmountEnabled,
                                onCheckedChange = actions.onSetKernelUmountEnabled
                            )
                        },
                        {
                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Security,
                                title = stringResource(id = R.string.settings_selinux_hide),
                                summary = selinuxHideSummary,
                                enabled = uiState.selinuxHideStatus == "supported",
                                checked = uiState.isSelinuxHideEnabled,
                                onCheckedChange = actions.onSetSelinuxHideEnabled
                            )
                        },
                        {
                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.AutoMirrored.Filled.Article,
                                title = stringResource(id = R.string.settings_sulog),
                                summary = sulogSummary,
                                enabled = uiState.sulogStatus == "supported",
                                checked = uiState.isSulogEnabled,
                                onCheckedChange = actions.onSetSulogEnabled
                            )
                        },
                        {
                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "unknown" -> stringResource(id = R.string.feature_status_unreadable_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Adb,
                                title = stringResource(id = R.string.settings_adb_root),
                                summary = adbRootSummary,
                                enabled = uiState.adbRootStatus == "supported",
                                checked = uiState.isAdbRootEnabled,
                                onCheckedChange = actions.onSetAdbRootEnabled
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.RestartAlt,
                                title = stringResource(id = R.string.settings_soft_reboot),
                                summary = stringResource(id = R.string.settings_soft_reboot_summary),
                                enabled = !uiState.isLateLoadMode,
                                checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                onCheckedChange = actions.onSetUseSoftReboot
                            )
                        },
                    )
                )

                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedSwitchItem(
                                icon = Icons.AutoMirrored.Filled.Rule,
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                                checked = uiState.isDefaultUmountModules,
                                onCheckedChange = actions.onSetDefaultUmountModules
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.DeveloperMode,
                                title = stringResource(id = R.string.enable_web_debugging),
                                summary = stringResource(id = R.string.enable_web_debugging_summary),
                                checked = uiState.enableWebDebugging,
                                onCheckedChange = actions.onSetEnableWebDebugging
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.FlashOn,
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                enabled = uiState.isLateLoadMode,
                                checked = uiState.autoJailbreak,
                                onCheckedChange = actions.onSetAutoJailbreak
                            )
                        }
                    )
                )
            }

            if (uiState.isLkmMode) {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            val uninstall = stringResource(id = R.string.settings_uninstall)
                            SegmentedListItem(
                                onClick = { showUninstallDialog.value = true },
                                enabled = !uiState.isLateLoadMode,
                                headlineContent = { Text(uninstall) },
                                leadingContent = { Icon(Icons.Filled.Delete, uninstall) }
                            )
                        }
                    )
                )
            }

            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = { showBottomSheet = true },
                            headlineContent = { Text(stringResource(id = R.string.send_log)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    stringResource(id = R.string.send_log)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenAbout,
                            headlineContent = { Text(stringResource(id = R.string.about)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Info,
                                    stringResource(id = R.string.about)
                                )
                            },
                        )
                    }
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (showBottomSheet) {
                SendLogBottomSheet(
                    onDismiss = { showBottomSheet = false },
                    snackbarHostState = snackBarHost,
                )
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
