package com.sevenk.core.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.sevenk.core.CrashReporter
import com.sevenk.core.Natives
import com.sevenk.core.ui.component.bottombar.BottomBar
import com.sevenk.core.ui.component.bottombar.MainPagerState
import com.sevenk.core.ui.component.bottombar.NavigationBadgeState
import com.sevenk.core.ui.component.bottombar.SideRail
import com.sevenk.core.ui.component.bottombar.rememberMainPagerState
import com.sevenk.core.ui.component.bottombar.useNavigationRail
import com.sevenk.core.ui.navigation3.IntentDispatcher
import com.sevenk.core.ui.navigation3.LocalNavigator
import com.sevenk.core.ui.navigation3.Navigator
import com.sevenk.core.ui.navigation3.Route
import com.sevenk.core.ui.navigation3.rememberNavigator
import com.sevenk.core.ui.screen.about.AboutScreen
import com.sevenk.core.ui.screen.appprofile.AppProfileScreen
import com.sevenk.core.ui.screen.colorpalette.ColorPaletteScreen
import com.sevenk.core.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import com.sevenk.core.ui.screen.flash.FlashScreen
import com.sevenk.core.ui.screen.home.HomePager
import com.sevenk.core.ui.screen.install.InstallScreen
import com.sevenk.core.ui.screen.module.ModulePager
import com.sevenk.core.ui.screen.modulerepo.ModuleRepoDetailScreen
import com.sevenk.core.ui.screen.modulerepo.ModuleRepoScreen
import com.sevenk.core.ui.screen.settings.SettingPager
import com.sevenk.core.ui.screen.sulog.SulogScreen
import com.sevenk.core.ui.security.Stealth
import com.sevenk.core.ui.security.StealthReceiver
import com.sevenk.core.ui.security.restartUiFresh
import com.sevenk.core.ui.screen.superuser.SuperUserPager
import com.sevenk.core.ui.screen.template.AppProfileTemplateScreen
import com.sevenk.core.ui.screen.templateeditor.TemplateEditorScreen
import com.sevenk.core.ui.theme.KernelSUTheme
import com.sevenk.core.ui.theme.LocalColorMode
import com.sevenk.core.ui.theme.LocalEnableBlur
import com.sevenk.core.ui.theme.LocalEnableFloatingBottomBar
import com.sevenk.core.ui.theme.LocalEnableFloatingBottomBarBlur
import com.sevenk.core.ui.theme.LocalEnableNavigationBadge
import com.sevenk.core.ui.theme.LocalFlatUi
import com.sevenk.core.ui.util.getSuperuserCount
import com.sevenk.core.ui.util.install
import com.sevenk.core.ui.util.rememberBlurBackdrop
import com.sevenk.core.ui.util.rememberContentReady
import com.sevenk.core.ui.viewmodel.MainActivityViewModel
import com.sevenk.core.ui.viewmodel.MainPagerConfig
import com.sevenk.core.ui.viewmodel.ModuleViewModel
import com.sevenk.core.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L

    /**
     * 崩溃上报：只要这个 Activity 至少走到过一次前台，正常收尾时就把会话标记删掉。
     * 没删掉 = 上次进程没正常收尾（可能崩了）→ 下次启动由 Application 触发后台抓取。
     * 允许误判（用户划掉 App 也可能没删到），所以最终是否上报由「日志里有没有我们自己的
     * 崩溃痕迹」决定，不靠这个标记。
     */
    private var cameToForeground = false

    /**
     * 「上次渲染这个界面时用的隐身状态」，记的是**内核 stealthState 的原始值**：
     * `0` = 已关、`1` = 已开，[STEALTH_STATE_UNKNOWN] = 基线本身不可信（构造期就没读到）。
     *
     * 为什么不用 Boolean：`Stealth.isEnabled()` 内部就是 `kernelState() == 1`，
     * 而 `kernelState()` 把「读失败」折成 `-1` —— 所以 `isEnabled()` **永远不会抛异常**，
     * 它的语义其实是"读不到 = false"。一次 ioctl 读失败（本项目已知会发生：appid 重扫
     * 期间标志翻转）就会被当成"隐身已关" → 白重建一次界面（500ms 闪屏）还把基线写错。
     * 只有记原始 Int，才能把「0」「1」「读不到」三种情况分开处理。
     *
     * 为什么需要它：本项目的界面状态是 **ViewModel 构造时算一次** 的，而 ViewModel 会跨
     * resume/recreate 存活。隐身标志却可能在本进程处于后台时被别处改掉
     * （拨号密令 [com.sevenk.core.ui.security.StealthReceiver]、内置网页管理器），
     * 于是「关掉隐身 → 切回前台」时界面拿的还是旧状态算出来的东西 = 看起来没刷新
     * （用户实测：小米/MIUI 上只有把 App 挂成小窗、真正重新可见一次才恢复）。
     *
     * 这里记下"渲染时用的值"，在 [refreshIfStealthChanged] 里回前台对比，
     * 只有**真的变了**才重建界面。
     *
     * 初值：[Stealth.kernelState] 自己已经 runCatching（不会抛，读失败返回 -1），
     * 但拿不到 0/1 时一律记 [STEALTH_STATE_UNKNOWN] —— 绝不把"不知道"当成某个具体状态。
     */
    private var lastSeenStealthState: Int = Stealth.kernelState().let {
        if (it == 0 || it == 1) it else STEALTH_STATE_UNKNOWN
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            !contentReady || SystemClock.uptimeMillis() - splashStartedAt < splashAnimationDurationMs
        }

        // ⚠️ 2026-09-19 修:`Natives.isManager` 是 JNI 调用(打到内核 / 读 /data/adb),
        //    native 库没加载、ioctl 被拒时都会抛;它在 onCreate 里,一抛就是**启动即闪退**。
        //    失败按"不是管理器"处理 → 后面所有分支都走非 root 的安全路径,不会崩。
        val isManager = runCatching { Natives.isManager }.getOrDefault(false)
        // ══════════════════════════════════════════════════════════════════
        // 🔴 结构性约束（v2.15 立，v2.16 修正措辞 —— 请勿违反）：
        //
        //   **App 的启动路径（Application.onCreate / MainActivity.onCreate 以及它们
        //     同步调用到的东西）不得发起 root shell / ksud 调用。**
        //   需要内核信息的界面（模块页 / 超级用户页 / 安装页 / 设置页 / 首页诊断），
        //   一律在**用户真的进入那个界面 / 点那个按钮**时按需查询。
        //
        // 为什么：v2.1 → v2.13 把启动期的 ksud 调用从个位数涨到十几个，
        //   每一次都是一次"可能失败"的机会；而失败值一旦流进 native / libsu，
        //   就会变成**真崩溃**。v2.15 定案的那条就是活例子：
        //   libsu 的 `RootService`（`KsuService`）往 cache 写一个可写 dex
        //   (`main.jar`) 再用当前 shell 跑 app_process；shell 没 root 时
        //   app_process 以 App 的 uid 加载它 → ART 拒绝 → abort()
        //   → `signal 6 (SIGABRT), code -1 (SI_QUEUE)`（真机 tombstone 实证）。
        //
        // 🟢 v2.16 修正：v2.15 那版注释写的是"唯二例外"，**漏了 `LegacyDataMigration`
        //    （在 Application.onCreate 里起后台线程跑 4 类 root 调用）** —— 注释与代码不符。
        //    现在把**全部**例外列全（这是启动链的权威清单，改代码时请同步维护）：
        //
        //   ① `install()`（下面 👇，**主线程**）：与上游逐字等价 ——
        //      上游 `MainActivity.onCreate` 就是 `if (isManager && UAPI 相等) install()`。
        //      不装 ksud 就完全没有 root 能力，必须启动时试一次。失败只留一行日志。
        //   ② 崩溃上报的后台抓取（`Application.onCreate` → daemon 线程）：
        //      只在"上次会话没正常收尾"时才跑，全程 runCatching。
        //   ③ `LegacyDataMigration.ensureMigratedOnce()`（`Application.onCreate` →
        //      daemon 线程）：改名迁移的门控预检 + 兼容软链兜底 + 首页诊断。
        //      **在后台线程上、不阻塞首帧、全部失败静默**；它是改名后"数据别丢"的兜底，
        //      拿不到 root 就什么都不做（留给 ksud 开机自己迁）。
        //   ④ 首页 `HomeViewModel` 的 root / SELinux 探测：**已挪到 IO 线程**
        //      （首帧用 `probeRoot = false`，见 HomeViewModel 注释）。上游是在
        //      ViewModel 构造里**同步**做的（更糟），我们保持现在这样。
        //   ⑤ 首页 `LaunchedEffect(isFullFeatured)` 的预加载 → `loadAppList()`：
        //      **这是上游自己的代码路径**（上游 `MainActivity.kt` 同位置同逻辑），
        //      被 `isFullFeatured`（= 内核认我们 && UAPI 够 && 有 root）门控。
        //
        //   ⇒ 净结果：启动期 ksud/root 调用从 v2.14 的十几个降到 **4 处**（①~④），
        //     其中只有 ① 在主线程（且与上游一致），②③ 有条件，④ 在 IO 线程。
        // ══════════════════════════════════════════════════════════════════

        // 首次运行:把内置默认壁纸播种成用户的壁纸(只做一次)。
        // 🟢 v2.15：这里以前**顺带**跑 `StealthCodeStore.sync()` —— 那要起 root shell，
        //    而设置页自己每次打开都会 sync（SettingsMiuix/SettingsMaterial 的 LaunchedEffect），
        //    所以启动时那次是**纯多余**的 root 调用，已删。壁纸播种只是拷 assets，不碰 root。
        com.sevenk.core.core.utils.AppExecutors.io.execute {
            runCatching {
                val seeded = com.sevenk.core.ui.util.WallpaperStore.seedDefaultIfNeeded(this)
                android.util.Log.i("7kkernel", "默认壁纸播种: $seeded")
            }
        }
        // 内核 UAPI >= 管理器即可安装 ksud(新内核向后兼容旧管理器)
        // ⚠️ 2026-09-19 修:`install()` 会往 /data/adb 拷 ksud,内部走 libsu 起 root shell,
        //    **拿不到 shell 就抛异常**;这里是主线程 onCreate、没有 try/catch → 打开即闪退。
        //    包 runCatching:最坏只是这次没装上 ksud(下次启动还会再试),绝不再崩;
        //    按用户要求**不挪到后台线程**、不弹任何框,失败只留一行日志。
        //
        // 🔴 D1(v2.2 修,粉丝实测故障):门槛以前是 `isManager && …`,而
        //    **隐身会把 `isManager` 压成 false** —— 隐身 = 内核在 GET_INFO 里不再上报
        //    MANAGER 标志(**权限本身完全不变**,见 ui/security/Stealth.kt 顶部注释)。
        //    后果:开着隐身的用户升级到新版后**永远不装 ksud** → 拿不到 root,
        //    表现就是「获取 Root 失败」+「内核组件(ksud)安装失败」。
        //    判据补上「内核明确说隐身开着」这条通路:能读到 stealthState()==1,
        //    本身就证明**我们家的内核在跑、而且认我们**(隐身开关就是我们的 ioctl)。
        //
        //    ⚠️ 为什么**不**直接无条件 `install()`(那是"能拿到 root 就装"的写法):
        //    真·没 root 的设备上,`install()` 必然失败 → 走
        //    `notifyInstallFailureOnce()` 弹一句「内核组件(ksud)安装失败」——
        //    那是**凭空造出来的假警报**,正好是用户最烦的那句话。所以只在
        //    「内核认我们」或「隐身开着」时试装;这两种情况下失败才是真失败。
        val stealthOn = runCatching { com.sevenk.core.ui.security.Stealth.isEnabled() }
            .getOrDefault(false)
        if (isManager || stealthOn) {
            // UAPI 门槛原样保留(isManager 为 false 时以前根本不会走到这里,
            //  所以这里必须自己 runCatching —— 别让一次 ioctl 失败把启动搞崩)
            val uapiOk = runCatching {
                Natives.kernelUAPIVersion >= Natives.managerUAPIVersion
            }.getOrDefault(false)
            if (uapiOk) {
                runCatching { install() }
                    .onFailure { android.util.Log.w("KernelSU", "启动时 install() 失败(忽略,不闪退): ", it) }
            }
        }

        // 确保桌面图标一直在(顺便把旧版本"隐藏图标"藏起来的图标找回来)
        Stealth.ensureLauncherVisible(this)

        // 🟢 v2.15 删除（原本是 v2.13 新增的启动期工作）：
        //   这里以前每次启动都跑一次 `WebAdminCli.syncPref(true)` —— 那是一条 root shell
        //   命令（`ksud webadmin sync`）。而**设置页自己**在打开网页管理器那一项时就会
        //   同步并读取地址（SettingsMiuix/SettingsMaterial 的 LaunchedEffect 里已补
        //   `WebAdminCli.syncPref(true)`），所以启动时那次是多余的 root 调用，已删。
        //   语义不变：升级安装后用户一进设置页就会把开关同步给 ksud。

        // 🟢 v2.15 删除（原本是 v2.12 新增的启动期工作）：
        //   这里以前每次启动都跑一次 `ksud module migrate-markers`，一条 root shell 命令。
        //   现在**登记簿整套已经删掉**：App 侧不再有任何相关代码，也**不会**再往模块目录
        //   里写我们自己的任何文件；模块目录由 ksud / 上游逻辑自己管。

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }
            // 「界面扁平化」(设置里的 flat_home,默认开):
            // 顶栏/底栏/首页卡片不画底色,只留文字和图标。
            // 挂在 WallpaperPrefs.observe() 上 → 改开关后立即生效,不用重启。
            // ⚠️ v0.13.157:隐身时**不再"素颜"**(外观与正常完全一致,见 security/Stealth.kt 顶部注释),
            //    所以这里不再看隐身状态 —— 个性化照常生效。
            val flatRevision = com.sevenk.core.ui.util.WallpaperPrefs.observe()
            val flatUi = remember(flatRevision) {
                com.sevenk.core.data.repository.SettingsRepositoryImpl().flatHome
            }

            // ⚠️ v2.20：「检测到旧版 7kimisu」那套**整套已删除**（用户要求：退回上游做法）。
            //    启动弹窗、设置页那一行、首页常驻卡全部撤掉；检测/卸载相关的代码文件也已
            //    从仓库里删干净，不再有任何入口。
            //
            //    ⚠️ v2.2 起"旧包还在就先不弹公告"的让位逻辑也随之撤掉：
            //    不再有第二个弹窗跟公告抢同一个 WindowDialog 通道（见
            //    ui/component/miuix/ModuleIcon.kt 顶部注释），公告恢复成"每次全新打开都弹"。

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalFlatUi provides flatUi,
                LocalUiMode provides uiMode,
            ) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                  // 崩溃上报弹窗：放在整套 UI 的最前面，且不受隐身/扁平化/公告等任何开关影响
                  // （隐身时也要能弹）。Dialog 是独立窗口，不参与下面的布局。
                  CrashReportDialog()
                  Box(modifier = Modifier.fillMaxSize()) {
                    // 个性化壁纸:在界面底下画一层背景(图片/视频),没设置时零开销
                    com.sevenk.core.ui.util.WallpaperHost {
                    IntentDispatcher(intentChannel = intentChannel)
                    // 每次全新打开软件都弹一次公告;隐身模式不弹(recreate 也不会重复弹)
                    com.sevenk.core.ui.component.AnnouncementPopup(
                        freshStart = savedInstanceState == null
                    )
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            // 隐身模式下强制停在首页:隐身时底部导航会消失,停在别的页就回不来了
                            // (首页是唯一的"出口页";详见下方 pagerState 处的说明)
                            // ⚠️ Natives.isFullFeatured() 是 JNI 调用,失败会抛;失败按 true(功能全开)处理
                            initialPage = if (runCatching { Natives.isFullFeatured() }.getOrDefault(true)) selectedMainPage else 0,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = {
                                when (val top = navigator.current()) {
                                    is Route.TemplateEditor -> {
                                        if (!top.readOnly) {
                                            navigator.setResult("template_edit", true)
                                        } else {
                                            navigator.pop()
                                        }
                                    }

                                    else -> navigator.pop()
                                }
                            },
                            entryProvider = entryProvider {
                                entry<Route.Main> { mainScreenEntry() }
                                entry<Route.About> { AboutScreen() }
                                entry<Route.Sulog> { SulogScreen() }
                                entry<Route.ColorPalette> { ColorPaletteScreen() }
                                entry<Route.AppProfileTemplate> { AppProfileTemplateScreen() }
                                entry<Route.TemplateEditor> { key -> TemplateEditorScreen(key.template, key.readOnly) }
                                entry<Route.AppProfile> { key -> AppProfileScreen(key.uid) }
                                entry<Route.ModuleRepo> { ModuleRepoScreen() }
                                entry<Route.ModuleRepoDetail> { key -> ModuleRepoDetailScreen(key.module) }
                                // ✅ 2026-09-20(v0.13.150):这里**不再**在隐身时把安装页弹回去。
                                //
                                // v0.13.149 曾在这里加了"隐身兜底 pop",同时把首页的安装入口点击也摘掉。
                                // 用户实测反馈:"点不了本身就很可疑"。
                                // 正确做法是**让安装页自己显示成"真·没 root 的设备"该有的样子**:
                                // 安装页(InstallScreen)会在隐身时把 root 当不可用,
                                // 「直接安装(推荐)」「安装到未使用槽位」(这两个要 root)自动消失,
                                // 只留下真·没 root 的 KernelSU 用户也会看到的
                                // 「选择文件并修补 / 下载文件并修补 / 使用本地 LKM 文件」。
                                // 所以这里照常进安装页,不放任何隐身判断(也避免误伤
                                // 「从别的 root 管理器转过来」——他们本来就该能进安装页)。
                                entry<Route.Install> { InstallScreen() }
                                entry<Route.Flash> { key -> FlashScreen(key.flashIt) }
                                entry<Route.ExecuteModuleAction> { key -> ExecuteModuleActionScreen(key.moduleId, key.fromShortcut) }
                                entry<Route.Home> { mainScreenEntry() }
                                entry<Route.SuperUser> { mainScreenEntry() }
                                entry<Route.Module> { mainScreenEntry() }
                                entry<Route.Settings> { mainScreenEntry() }
                            }
                        )
                    }

                    when (uiMode) {
                        UiMode.Material -> androidx.compose.material3.Scaffold(
                            containerColor = MaterialTheme.colorScheme.background
                        ) { navDisplay() }

                        UiMode.Miuix -> Scaffold { navDisplay() }
                    }
                    }
                    // 下雪特效:铺在最上层,纯视觉、不吃触摸;关掉时完全不参与组合(零开销)
                    // ⚠️ v0.13.157:隐身时也照常画(没 root 的用户也有这个特效,不是 root 特征)
                    if (uiState.snowEnabled) {
                        com.sevenk.core.ui.component.SnowfallOverlay(
                            sizeScale = uiState.snowSize,
                            speedScale = uiState.snowSpeed,
                            tiltEnabled = uiState.gravityEnabled,
                            gravityMode = uiState.gravityMode,
                        )
                    }
                    // 巨魔雨:蓝巨魔图标翻滚下落,同样不吃触摸
                    if (uiState.trollRainEnabled) {
                        com.sevenk.core.ui.component.TrollRainOverlay(
                            sizeScale = uiState.trollRainSize,
                            speedScale = uiState.trollRainSpeed,
                            tiltEnabled = uiState.gravityEnabled,
                            gravityMode = uiState.gravityMode,
                        )
                    }
                  }
                    SideEffect { contentReady = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameToForeground = true
        // 已经回到管理器前台 → 拨号密令留下的「已关闭隐身」辅助通知就没用了，撤掉。
        // 通知 id 由 [StealthReceiver.NOTIFICATION_ID] 统一提供（同一个常量，不用各写一份）。
        // 整段 runCatching：撤通知失败（系统通知服务异常）绝不能影响 App 启动后的任何行为。
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(StealthReceiver.NOTIFICATION_ID)
        }.onFailure {
            android.util.Log.w(TAG, "撤掉「已关闭隐身」通知失败(忽略，不影响使用)", it)
        }
        // 回前台再自检一次：隐身标志可能在后台被拨号密令 / 网页管理器改掉了
        refreshIfStealthChanged()
    }

    /**
     * 回前台核对「内核里的隐身状态」是否和上次渲染时用的不一样，不一样就整界面重建。
     *
     * 为什么必须补这一手：Android 10+ 禁止后台启动 Activity，拨号密令在后台把内核标志
     * 关掉后，[com.sevenk.core.ui.security.StealthReceiver] 里的 startActivity 会被
     * 系统拦下；而本项目的界面状态是 ViewModel 构造时算一次的、ViewModel 又跨 resume
     * 存活 —— 所以"光切回前台"拿的还是隐身时算出来的旧状态，界面不会刷新
     * （用户实测：小米/MIUI 上必须把 App 挂成小窗才恢复）。这里用
     * [restartUiFresh]（清 ViewModelStore 再 recreate）把这个缺口补上。
     *
     * ⚠️ 读失败 ≠ 隐身已关（[Stealth.kernelState] 读不到时返回 -1，注意
     * `Stealth.isEnabled()` 只是 `kernelState() == 1`，它**不会抛异常**，
     * 拿它做兜底等于把"读不到"当成 false）：一次读失败绝不能触发重建、也不能写坏基线。
     *
     * 防抖（为什么不会 recreate 循环）：**先写字段、再重建**。
     * recreate 之后那一次 onResume 读到的 [lastSeenStealthState] 已经等于当前真值，
     * `now == lastSeenStealthState` 成立 → 不会再触发一次，循环从根上断开。
     * 状态没变时**一次重建都不做**，不闪屏、不打断用户。
     *
     * 整段 runCatching：只留日志，绝不把 App 带崩；内核 ioctl 是轻量调用，
     * 不违反"主线程不做重活"。
     */
    private fun refreshIfStealthChanged() {
        runCatching {
            // [Stealth.kernelState] 内部已 runCatching（读失败返回 -1），本身不会抛。
            val now = Stealth.kernelState()

            // ① 读失败（-1，或任何非 0/1 的意外值）：**绝不能**算成"隐身已关"。
            //    不动基线、不重建，等下一次 onResume 再对一次 —— 宁可晚一轮，也不误重建。
            if (now != 0 && now != 1) {
                android.util.Log.w(TAG, "回前台读隐身状态失败(state=$now)，保持基线不动、不重建")
                return@runCatching
            }

            // ② 和上次渲染时用的值一样：什么都不做。
            if (now == lastSeenStealthState) return@runCatching

            // ③ 基线本身未知（构造期就没读到）：界面本来就是照**当前**状态渲染的，
            //    所以只把基线对齐到 now，**不重建**（避免启动后无谓地闪一次）。
            if (lastSeenStealthState == STEALTH_STATE_UNKNOWN) {
                android.util.Log.i(
                    TAG,
                    "隐身基线未知，对齐为 $now（界面本就是按当前状态渲染的，不重建）"
                )
                lastSeenStealthState = now
                return@runCatching
            }

            // ④ 真的变了：先记下新值（防抖），再重建界面。
            android.util.Log.i(
                TAG,
                "回前台发现隐身状态变了：$lastSeenStealthState -> $now，重建界面"
            )
            // ⚠️ 顺序不能反：先记新值，再重建（防抖，见上面的说明）
            lastSeenStealthState = now
            // 已经要销毁的界面不再重建（那次 recreate 之后会有全新实例读到新状态）
            if (!isFinishing && !isDestroyed) {
                restartUiFresh(this)
            }
        }.onFailure {
            android.util.Log.w(TAG, "回前台核对隐身状态失败(忽略，不影响使用)", it)
        }
    }

    // 正常收尾的兜底（崩溃上报用）：删掉会话标记 = 这次不是崩着退出的。
    // ⚠️ 允许误判 —— 用户从最近任务划掉 App、隐身重建界面都可能走到这里，
    //    标记没了就只是"这次不上报"，绝不会误报；最终是否上报由日志内容决定。
    override fun onStop() {
        super.onStop()
        clearSessionMarkerIfForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSessionMarkerIfForeground()
    }

    /** 删会话标记；整段 runCatching，绝不让收尾路径把 App 带崩。 */
    private fun clearSessionMarkerIfForeground() {
        if (!cameToForeground) return
        runCatching { CrashReporter.clearSessionMarker(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }

    private companion object {
        /**
         * 「基线不可信」：构造这一刻 / 回前台那一次就没读到内核隐身标志（读失败、内核不支持）。
         * 故意和内核自己的 `-1` 分开：`-1` 表示"**这一次**读失败"（下次还能再读），
         * [STEALTH_STATE_UNKNOWN] 表示"**基线本身**没有可信值"（只能对齐、不能据此重建）。
         */
        const val STEALTH_STATE_UNKNOWN = -2

        /** 隐身相关日志统一用这个 tag（与 StealthReceiver 一致） */
        const val TAG = "7kkernel-stealth"
    }

}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)

    // 隐身模式下底部导航会消失。如果此时停在"设置/超级用户"页,就再也回不到首页
    // (首页是唯一的"出口页"),等于被困死在里面。
    // rememberPagerState 内部是 rememberSaveable,只改 initialPage 没用,
    // 所以按 isFullFeatured 做 key,隐身时直接把 pager 重建、固定在第 0 页。
    // ⚠️ 2026-09-19 修(G3):`Natives.isFullFeatured()` 是 JNI 调用(native 没加载、
    //    ioctl 被拒都会抛),它就在组合期、会随重组反复执行 —— 一抛就是闪退。
    //    失败按 **true**(功能全开)处理:绝不因为一次探测失败把界面降级成"未安装"。
    val isFullFeatured = runCatching { Natives.isFullFeatured() }.getOrDefault(true)
    val pagerState = key(isFullFeatured) {
        rememberPagerState(
            initialPage = if (isFullFeatured) initialPage else 0,
            pageCount = { MainPagerConfig.PAGE_COUNT },
        )
    }
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }

    // 双保险:等布局完成后再滚一次(scrollToPage 在布局前调用会被丢掉)
    LaunchedEffect(isFullFeatured, pagerState) {
        if (!isFullFeatured) {
            withFrameNanos { }
            runCatching { pagerState.scrollToPage(0) }
        }
    }

    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state -> state.groupedApps.count { it.anyAllowSu } }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(0)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured) {
        if (!isFullFeatured || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing) moduleViewModel.fetchModuleList()
            moduleViewModel.uiState.first { it.hasLoaded }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    // Loading the app list just for a badge is too expensive; read the kernel allowlist instead.
    var superuserCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(badgeEnabled, grantedUidCount) {
        superuserCount = if (badgeEnabled) withContext(Dispatchers.IO) { getSuperuserCount() } else 0
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleUiState.modules.count { it.enabled },
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface // Blur is not used in Material, this is just a placeholder
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) 3 else 0,
                    overscrollEffect = null,
                    userScrollEnabled = userScrollEnabled,
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (contentReady || isCurrentPage) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> if (contentReady || isCurrentPage) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> if (contentReady || isCurrentPage) ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Row {
                        SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }

                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    bottomBar = bottomBar,
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }

                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}


@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}
