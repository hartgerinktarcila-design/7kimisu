package com.sevenk.core.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.data.SettingsRepository
import com.sevenk.core.security.Stealth
import com.sevenk.core.security.StealthReceiver
import com.sevenk.core.security.restartUiFresh
import com.sevenk.core.ui.navigation.LocalNavigator
import com.sevenk.core.ui.navigation.Route
import com.sevenk.core.ui.navigation.rememberNavigator
import com.sevenk.core.ui.screen.about.AboutScreen
import com.sevenk.core.ui.screen.flash.FlashScreen
import com.sevenk.core.ui.screen.home.HomeScreen
import com.sevenk.core.ui.screen.module.ModuleScreen
import com.sevenk.core.ui.screen.settings.SettingsScreen
import com.sevenk.core.ui.screen.superuser.SuperUserScreen
import com.sevenk.core.ui.theme.ColorMode
import com.sevenk.core.ui.theme.KernelSUTheme
import com.sevenk.core.ui.theme.LocalColorMode
import com.sevenk.core.ui.util.install
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.nav.core.NavDisplay

private const val TAG = "7kimisu"

class MainActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)

    /**
     * 「上次渲染这个界面时用的隐身状态」，记的是内核 stealthState 的原始值：
     * 0 = 已关、1 = 已开，STEALTH_STATE_UNKNOWN = 基线本身不可信。
     * 隐身标志可能在本进程处于后台时被别处改掉（拨号密令），
     * 回前台对比，只有真的变了才重建界面。
     */
    private var lastSeenStealthState: Int = Stealth.kernelState().let {
        if (it == 0 || it == 1) it else STEALTH_STATE_UNKNOWN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Natives.isManager 是 JNI 调用，失败按"不是管理器"处理，走非 root 的安全路径。
        val isManager = runCatching { Natives.isManager }.getOrDefault(false)

        // 与旧版一致：只在「内核认我们」或「隐身开着」且 UAPI 够时试装 ksud。
        // （隐身会把 isManager 压成 false，但权限不变；不装 ksud 就没有 root 能力。）
        val stealthOn = runCatching { Stealth.isEnabled() }.getOrDefault(false)
        if (isManager || stealthOn) {
            val uapiOk = runCatching {
                Natives.kernelUAPIVersion >= Natives.managerUAPIVersion
            }.getOrDefault(false)
            if (uapiOk) {
                runCatching { install() }
                    .onFailure { Log.w(TAG, "启动时 install() 失败(忽略,不闪退): ", it) }
            }
        }

        // 确保桌面图标一直在
        Stealth.ensureLauncherVisible(this)

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val settingsRepo = remember { SettingsRepository() }
            var colorMode by remember { mutableIntStateOf(settingsRepo.themeMode) }

            // prefs 改动实时生效（设置页切主题不用重启）
            DisposableEffect(settingsRepo) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == "color_mode") {
                        colorMode = settingsRepo.themeMode
                    }
                }
                settingsRepo.registerListener(listener)
                onDispose { settingsRepo.unregisterListener(listener) }
            }

            val darkMode = ColorMode.fromValue(colorMode).isDark ||
                (ColorMode.fromValue(colorMode).isSystem && isSystemInDarkTheme())

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

            // 处理外部 intent：zip VIEW → 模块安装页
            LaunchedIntentEffect(intentChannel, navigator)

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalColorMode provides colorMode,
            ) {
                KernelSUTheme(colorMode = ColorMode.fromValue(colorMode)) {
                    Scaffold {
                        NavDisplay(
                            backStack = navigator.backStack,
                            onBack = { navigator.pop() },
                        ) {
                            entry<Route.Main> { MainScreen() }
                            entry<Route.Flash> { key ->
                                FlashScreen(flash = key)
                            }
                            entry<Route.FlashModule> { key ->
                                FlashScreen(moduleZip = key)
                            }
                            entry<Route.About> { AboutScreen() }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台 → 拨号密令留下的「已关闭隐身」辅助通知就没用了，撤掉。
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(StealthReceiver.NOTIFICATION_ID)
        }
        refreshIfStealthChanged()
    }

    /**
     * 回前台核对「内核里的隐身状态」是否和上次渲染时用的不一样，不一样就整界面重建。
     * 读失败（-1）绝不能算成"隐身已关"：不动基线、不重建。
     * 防抖：先写字段、再重建（recreate 后那次 onResume 读到的新基线已等于真值）。
     */
    private fun refreshIfStealthChanged() {
        runCatching {
            val now = Stealth.kernelState()
            if (now != 0 && now != 1) {
                Log.w(TAG, "回前台读隐身状态失败(state=$now)，保持基线不动、不重建")
                return@runCatching
            }
            if (now == lastSeenStealthState) return@runCatching
            if (lastSeenStealthState == STEALTH_STATE_UNKNOWN) {
                lastSeenStealthState = now
                return@runCatching
            }
            Log.i(TAG, "回前台发现隐身状态变了：$lastSeenStealthState -> $now，重建界面")
            lastSeenStealthState = now
            if (!isFinishing && !isDestroyed) {
                restartUiFresh(this)
            }
        }.onFailure {
            Log.w(TAG, "回前台核对隐身状态失败(忽略，不影响使用)", it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }

    private companion object {
        /** 「基线不可信」：构造这一刻就没读到内核隐身标志 */
        const val STEALTH_STATE_UNKNOWN = -2
    }
}

/** 收集外部 intent：application/zip 的 VIEW → 推模块安装页；ksu://webui → TODO 后置。 */
@Composable
private fun LaunchedIntentEffect(
    intentChannel: Channel<Intent>,
    navigator: com.sevenk.core.ui.navigation.Navigator,
) {
    androidx.compose.runtime.LaunchedEffect(intentChannel) {
        for (intent in intentChannel) {
            val data = intent.data ?: continue
            when {
                intent.action == Intent.ACTION_VIEW &&
                    (intent.type == "application/zip" || data.toString().endsWith(".zip")) -> {
                    navigator.push(Route.FlashModule(zipUri = data.toString()))
                }
                // TODO: ksu://webui 深链（网页管理器 webui）后置
                else -> Log.i(TAG, "未处理的 intent: ${intent.action} $data")
            }
        }
    }
}

/** 四主页面索引 */
private const val PAGE_HOME = 0
private const val PAGE_SUPERUSER = 1
private const val PAGE_MODULE = 2
private const val PAGE_SETTINGS = 3
private const val PAGE_COUNT = 4

@Composable
fun MainScreen() {
    val navigator = LocalNavigator.current
    // Natives.isFullFeatured() 是 JNI 调用，失败按 true（功能全开）处理：
    // 绝不因为一次探测失败把界面降级成"未安装"。
    val isFullFeatured = runCatching { Natives.isFullFeatured() }.getOrDefault(true)

    // 隐身/未认主时底栏消失、pager 锁在首页（首页是唯一的"出口页"）。
    // rememberPagerState 内部是 rememberSaveable，改 initialPage 没用，
    // 所以按 isFullFeatured 做 key，隐身时直接把 pager 重建、固定在第 0 页。
    val pagerState = androidx.compose.runtime.key(isFullFeatured) {
        rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })
    }
    val scope = rememberCoroutineScope()

    // 在非首页按返回键：先回首页，而不是直接退出 App
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isFullFeatured && pagerState.currentPage != PAGE_HOME,
        onBackCompleted = { scope.launch { pagerState.animateScrollToPage(PAGE_HOME) } },
    )

    val labels = listOf(
        stringResource(R.string.home),
        stringResource(R.string.superuser),
        stringResource(R.string.module),
        stringResource(R.string.settings),
    )
    val icons = listOf(
        Icons.Rounded.Cottage,
        Icons.Rounded.Security,
        Icons.Rounded.Extension,
        Icons.Rounded.Settings,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = isFullFeatured,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    PAGE_HOME -> HomeScreen()
                    PAGE_SUPERUSER -> if (isFullFeatured) SuperUserScreen()
                    PAGE_MODULE -> if (isFullFeatured) ModuleScreen()
                    PAGE_SETTINGS -> if (isFullFeatured) SettingsScreen()
                }
            }
        }
        if (isFullFeatured) {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = icons[index],
                        label = label,
                    )
                }
            }
        }
    }
}
