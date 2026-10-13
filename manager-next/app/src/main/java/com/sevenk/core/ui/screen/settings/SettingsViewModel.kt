package com.sevenk.core.ui.screen.settings

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
import com.sevenk.core.data.SettingsRepository
import com.sevenk.core.ui.theme.ColorMode
import com.sevenk.core.ui.util.execKsud
import com.sevenk.core.ui.util.getFeaturePersistValue
import com.sevenk.core.ui.util.getFeatureStatus
import com.sevenk.core.ui.util.getRootShell
import com.topjohnwu.superuser.ShellUtils

/** 首版设置的四个内核 feature 开关 */
private const val FEATURE_SULOG = "sulog"
private const val FEATURE_ADB_ROOT = "adb_root"
private const val FEATURE_SELINUX_HIDE = "selinux_hide"
private const val FEATURE_SHELL_SU_ADB_ONLY = "shell_su_adb_only"

/**
 * 一个内核 feature 的界面状态。
 * status: supported / managed / unsupported / unknown（只认 ksud 吐出来的结论，
 *         读不到一律 unknown —— 界面显示"读不到内核状态"，绝不假装可用）。
 */
data class FeatureUiState(
    val status: String = "unknown",
    val enabled: Boolean = false,
)

data class SettingsUiState(
    val colorMode: Int = ColorMode.SYSTEM.value,
    val stealthCode: String = "70707",
    val isLkmMode: Boolean = false,
    val isManager: Boolean = false,
    val sulog: FeatureUiState = FeatureUiState(),
    val adbRoot: FeatureUiState = FeatureUiState(),
    val selinuxHide: FeatureUiState = FeatureUiState(),
    val shellSuAdbOnly: FeatureUiState = FeatureUiState(),
)

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // 协程里未捕获的异常 = 闪退：每一项独立兜底，失败退化成"上一次的值"。
            val prev = _uiState.value
            val colorMode = runCatching { repo.themeMode }.getOrDefault(prev.colorMode)
            val stealthCode = runCatching { repo.stealthCode }.getOrDefault(prev.stealthCode)
            val isManager = runCatching { Natives.isManager }.getOrDefault(prev.isManager)
            val isLkmMode = runCatching { Natives.isLkmMode }.getOrDefault(prev.isLkmMode)

            // feature 状态都要起 root shell → IO 线程
            val sulog = withContext(Dispatchers.IO) { readFeature(FEATURE_SULOG, prev.sulog) }
            val adbRoot = withContext(Dispatchers.IO) { readFeature(FEATURE_ADB_ROOT, prev.adbRoot) }
            val shellSuAdbOnly = withContext(Dispatchers.IO) {
                readFeature(FEATURE_SHELL_SU_ADB_ONLY, prev.shellSuAdbOnly)
            }
            // selinux_hide 走 Natives（ioctl），不走 ksud feature get
            val selinuxHide = withContext(Dispatchers.IO) {
                val status = runCatching { getFeatureStatus(FEATURE_SELINUX_HIDE) }
                    .getOrDefault(prev.selinuxHide.status)
                val enabled = runCatching { Natives.isSelinuxHideEnabled() }
                    .getOrDefault(prev.selinuxHide.enabled)
                FeatureUiState(status = status, enabled = enabled)
            }

            _uiState.update {
                it.copy(
                    colorMode = colorMode,
                    stealthCode = stealthCode,
                    isManager = isManager,
                    isLkmMode = isLkmMode,
                    sulog = sulog,
                    adbRoot = adbRoot,
                    selinuxHide = selinuxHide,
                    shellSuAdbOnly = shellSuAdbOnly,
                )
            }
        }
    }

    /** 读一个 feature 的（支持状态, 持久化值）。失败保持上一次的值。 */
    private suspend fun readFeature(name: String, prev: FeatureUiState): FeatureUiState {
        val status = runCatching { getFeatureStatus(name) }.getOrDefault(prev.status)
        val value = runCatching { getFeaturePersistValue(name) }.getOrNull()
        return FeatureUiState(status = status, enabled = value == 1L)
    }

    fun setColorMode(mode: Int) {
        repo.themeMode = mode
        _uiState.update { it.copy(colorMode = mode) }
    }

    /** 只写 App 设置这一份；磁盘那份（跨卸载）由 SettingsScreen 异步调 StealthCodeStore.write */
    fun setStealthCode(code: String) {
        repo.stealthCode = code
        _uiState.update { it.copy(stealthCode = code) }
    }

    /**
     * 通用 feature 开关：ksud feature set → 成功后 feature save 落盘。
     * @return 是否真的写成（失败时界面不翻开关）
     */
    private suspend fun setFeature(name: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val ok = runCatching {
                execKsud("feature set $name ${if (enabled) 1 else 0}", true)
            }.getOrDefault(false)
            if (ok) {
                runCatching { execKsud("feature save", true) }
            }
            ok
        }

    fun setSulogEnabled(enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (setFeature(FEATURE_SULOG, enabled)) {
                _uiState.update { it.copy(sulog = it.sulog.copy(enabled = enabled)) }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    fun setAdbRootEnabled(enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (setFeature(FEATURE_ADB_ROOT, enabled)) {
                // 重启 adbd 让补丁生效（与旧版一致）
                withContext(Dispatchers.IO) {
                    runCatching {
                        ShellUtils.fastCmd(getRootShell(), "setprop ctl.restart adbd")
                    }
                }
                _uiState.update { it.copy(adbRoot = it.adbRoot.copy(enabled = enabled)) }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    fun setShellSuAdbOnlyEnabled(enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (setFeature(FEATURE_SHELL_SU_ADB_ONLY, enabled)) {
                _uiState.update { it.copy(shellSuAdbOnly = it.shellSuAdbOnly.copy(enabled = enabled)) }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    /**
     * selinux_hide 走 Natives ioctl，返回值是 Int 状态码：
     * 0 = 成功；-EAGAIN = 要重启才生效；其它负数 = 失败。
     * onDone 在主线程回调（调用方要弹 Toast）。
     */
    fun setSelinuxHideEnabled(enabled: Boolean, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching { Natives.setSelinuxHideEnabled(enabled) }.getOrDefault(-1)
            if (status == 0 || status == -android.system.OsConstants.EAGAIN) {
                runCatching { execKsud("feature save", true) }
                _uiState.update { it.copy(selinuxHide = it.selinuxHide.copy(enabled = enabled)) }
            }
            withContext(Dispatchers.Main) { onDone(status) }
        }
    }
}
