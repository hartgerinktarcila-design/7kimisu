package com.sevenk.core.ui.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 壁纸设置的"响应式"入口。
 *
 * 为什么需要它:MainActivityViewModel 用的是 MutableStateFlow,而 StateFlow
 * 只在【值真的变了】才发射。壁纸的 pref key 虽然加进了它的监听列表,
 * 但 MainActivityUiState 里的字段没变 → 值相等 → 不发射 → 界面不重组,
 * 于是壁纸层和主题透明都不会生效。
 *
 * 这里自己挂一个 pref 监听,把"变了"这件事变成一个 Compose 状态,
 * 谁读了它谁就会在壁纸设置变化时重组。
 */
object WallpaperPrefs {

    var revision by mutableIntStateOf(0)
        private set

    private var registered = false

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 🟠（v2.18）快照状态**只能在主线程改**。`OnSharedPreferenceChangeListener` 是在
     * **写 pref 的那个线程**上同步回调的，而本项目的壁纸写入有两条明确的非主线程路径：
     * `SettingsMiuix/SettingsMaterial` 的 `AppExecutors.io.execute { WallpaperStore.applyBuiltin(...) }`、
     * `WallpaperHost` 的 `withContext(Dispatchers.IO) { healIfBroken() }`（都会写 `wallpaper_*`）。
     * 旧代码在这里直接 `revision++` —— 跨线程写全局快照状态，轻则"换完壁纸界面没刷新"，
     * 重则偶发 `IllegalStateException: Reading a state that was created after the snapshot was taken`。
     * 与 `WallpaperStore.bump()`（v2.14 修过的同一个坑）保持同一种写法。
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (
                key.startsWith("wallpaper") ||
                        key.startsWith("ui_translucent") ||
                        key.startsWith("status_") ||
                        key.startsWith("nav_icon") ||
                        key == "flat_home"
                )
        ) {
            if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
                revision++
            } else {
                mainHandler.post { revision++ }
            }
        }
    }

    /** 在 Composable 里调用;读它的返回值即可获得"壁纸设置变化就重组"的能力 */
    @Composable
    fun observe(): Int {
        val context = LocalContext.current
        DisposableEffect(context) {
            if (!registered) {
                registered = true
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(listener)
            }
            onDispose { }
        }
        return revision
    }
}
