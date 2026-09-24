package com.sevenk.core.ui.viewmodel

import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.data.repository.SettingsRepository
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.screen.settings.SettingsUiState
import com.sevenk.core.ui.theme.ColorMode
import com.sevenk.core.ui.util.ThemeLock

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // ⚠️ 协程里未捕获的异常 = 闪退。这里读的是 SharedPreferences + JNI（Natives.*）+
            //    ksud feature 状态：内核太老 / UAPI 不匹配 / prefs 被写坏都会抛。
            //    所以**每一项独立兜底**，失败就退化成"上一次的值"（安全默认值），
            //    uiState 结构完全不变；init { refresh() } 也因此不会在构造 ViewModel 时炸掉。
            val prev = _uiState.value
            val checkUpdate = runCatching { repo.checkUpdate }.getOrDefault(prev.checkUpdate)
            val checkModuleUpdate = runCatching { repo.checkModuleUpdate }.getOrDefault(prev.checkModuleUpdate)
            val themeMode = runCatching { repo.themeMode }.getOrDefault(prev.themeMode)
            val miuixMonet = runCatching { repo.miuixMonet }.getOrDefault(prev.miuixMonet)
            val keyColor = runCatching { repo.keyColor }.getOrDefault(prev.keyColor)
            val enablePredictiveBack = runCatching { repo.enablePredictiveBack }.getOrDefault(prev.enablePredictiveBack)
            val enableBlur = runCatching { repo.enableBlur }.getOrDefault(prev.enableBlur)
            val enableFloatingBottomBar = runCatching { repo.enableFloatingBottomBar }.getOrDefault(prev.enableFloatingBottomBar)
            val enableFloatingBottomBarBlur = runCatching { repo.enableFloatingBottomBarBlur }.getOrDefault(prev.enableFloatingBottomBarBlur)
            val enableNavigationBadge = runCatching { repo.enableNavigationBadge }.getOrDefault(prev.enableNavigationBadge)
            val pageScale = runCatching { repo.pageScale }.getOrDefault(prev.pageScale)
            val enableWebDebugging = runCatching { repo.enableWebDebugging }.getOrDefault(prev.enableWebDebugging)
            val colorStyle = runCatching { repo.colorStyle }.getOrDefault(prev.colorStyle)
            val colorSpec = runCatching { repo.colorSpec }.getOrDefault(prev.colorSpec)
            val isLkmMode = runCatching { repo.isLkmMode() }.getOrDefault(prev.isLkmMode)

            // Async loading for natives/features
            val suCompatStatus = runCatching { repo.getSuCompatStatus() }.getOrDefault(prev.suCompatStatus)
            val suCompatPersistValue = runCatching { repo.getSuCompatPersistValue() }.getOrNull()
            val isSuEnabled = runCatching { repo.isSuEnabled() }.getOrDefault(prev.isSuEnabled)

            val suCompatMode = if (suCompatPersistValue == 0L) 2 else if (!isSuEnabled) 1 else 0

            val kernelUmountStatus = runCatching { repo.getKernelUmountStatus() }.getOrDefault(prev.kernelUmountStatus)
            val isKernelUmountEnabled = runCatching { repo.isKernelUmountEnabled() }.getOrDefault(prev.isKernelUmountEnabled)
            val selinuxHideStatus = runCatching { repo.getSelinuxHideStatus() }.getOrDefault(prev.selinuxHideStatus)
            val isSelinuxHideEnabled = runCatching { repo.isSelinuxHideEnabled() }.getOrDefault(prev.isSelinuxHideEnabled)
            val sulogStatus = runCatching { repo.getSulogStatus() }.getOrDefault(prev.sulogStatus)
            val isSulogEnabled = runCatching { repo.getSulogPersistValue() }.getOrNull() == 1L
            val adbRootStatus = runCatching { repo.getAdbRootStatus() }.getOrDefault(prev.adbRootStatus)
            val isAdbRootEnabled = runCatching { repo.getAdbRootPersistValue() }.getOrNull() == 1L
            val isDefaultUmountModules = runCatching { repo.isDefaultUmountModules() }.getOrDefault(prev.isDefaultUmountModules)
            val uiMode = runCatching { repo.uiMode }.getOrDefault(prev.uiMode)
            val autoJailbreak = runCatching { repo.autoJailbreak }.getOrDefault(prev.autoJailbreak)
            val useSoftReboot = runCatching { repo.useSoftReboot }.getOrDefault(prev.useSoftReboot)
            val isLateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(prev.isLateLoadMode)

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    checkModuleUpdate = checkModuleUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    enableNavigationBadge = enableNavigationBadge,
                    pageScale = pageScale,
                    enableWebDebugging = enableWebDebugging,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    suCompatStatus = suCompatStatus,
                    suCompatMode = suCompatMode,
                    isSuEnabled = isSuEnabled,
                    adbRootStatus = adbRootStatus,
                    isAdbRootEnabled = isAdbRootEnabled,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    selinuxHideStatus = selinuxHideStatus,
                    isSelinuxHideEnabled = isSelinuxHideEnabled,
                    sulogStatus = sulogStatus,
                    isSulogEnabled = isSulogEnabled,
                    isDefaultUmountModules = isDefaultUmountModules,
                    isLkmMode = isLkmMode,
                    autoJailbreak = autoJailbreak,
                    useSoftReboot = useSoftReboot,
                    isLateLoadMode = isLateLoadMode,
                )
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        // 主题锁定:界面风格(miuix/material)可以切,但颜色模式固定不动
        val newThemeMode = if (ThemeLock.ENABLED) {
            ThemeLock.THEME_MODE
        } else when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setCheckModuleUpdate(enabled: Boolean) {
        repo.checkModuleUpdate = enabled
        _uiState.update { it.copy(checkModuleUpdate = enabled) }
    }

    fun setThemeMode(mode: Int) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableBlur(enabled: Boolean) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setEnableNavigationBadge(enabled: Boolean) {
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableNavigationBadge = enabled
        _uiState.update { it.copy(enableNavigationBadge = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setEnableWebDebugging(enabled: Boolean) {
        repo.enableWebDebugging = enabled
        _uiState.update { it.copy(enableWebDebugging = enabled) }
    }

    fun setSuCompatMode(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                0 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(0)
                    _uiState.update { it.copy(suCompatMode = 0, isSuEnabled = true) }
                }

                1 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    if (repo.setSuEnabled(false)) {
                        // "Disable until reboot" implies it should be enabled on next boot.
                        // We set the preference to 0 (Enabled) to match the persistent state.
                        repo.setSuCompatModePref(0)
                        _uiState.update { it.copy(suCompatMode = 1, isSuEnabled = false) }
                    }
                }

                2 -> if (repo.setSuEnabled(false)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(2)
                    _uiState.update { it.copy(suCompatMode = 2, isSuEnabled = false) }
                }
            }
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setKernelUmountEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isKernelUmountEnabled = enabled) }
            }
        }
    }

    fun setSelinuxHideEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = repo.setSelinuxHideEnabled(enabled)
            repo.execKsudFeatureSave()
            _uiState.update { it.copy(isSelinuxHideEnabled = enabled) }
            when (status) {
                0 -> {}
                -OsConstants.EAGAIN -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, R.string.settings_selinux_hide_reboot_required,
                            Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, ksuApp.getString(R.string.settings_selinux_hide_failed, status),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun setAutoJailbreak(enabled: Boolean) {
        repo.autoJailbreak = enabled
        _uiState.update { it.copy(autoJailbreak = enabled) }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }

    fun setSulogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setSulogEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isSulogEnabled = enabled) }
            }
        }
    }

    fun setAdbRootEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setAdbRootEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isAdbRootEnabled = enabled) }
            }
        }
    }

    fun setDefaultUmountModules(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setDefaultUmountModules(enabled)) {
                _uiState.update { it.copy(isDefaultUmountModules = enabled) }
            }
        }
    }
}
