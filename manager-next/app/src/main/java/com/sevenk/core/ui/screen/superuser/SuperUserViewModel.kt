package com.sevenk.core.ui.screen.superuser

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.Natives
import com.sevenk.core.data.SettingsRepository
import com.sevenk.core.data.model.AppInfo
import com.sevenk.core.ksuApp
import java.text.Collator
import java.util.Locale

data class SuperUserUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val showSystemApps: Boolean = false,
    val error: Throwable? = null,
) {
    /** 界面实际展示的列表：已授权/自定义配置的应用始终可见；系统应用按开关过滤 */
    val visibleApps: List<AppInfo>
        get() = apps.filter {
            if (it.allowSu || it.hasCustomProfile) return@filter true
            if (showSystemApps) return@filter true
            val flags = it.packageInfo.applicationInfo?.flags ?: 0
            flags.and(ApplicationInfo.FLAG_SYSTEM) == 0
        }
}

class SuperUserViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SuperUserUiState(showSystemApps = runCatching { settingsRepo.superuserShowSystemApps }.getOrDefault(false))
    )
    val uiState: StateFlow<SuperUserUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun toggleShowSystemApps() {
        val newValue = !_uiState.value.showSystemApps
        settingsRepo.superuserShowSystemApps = newValue
        _uiState.update { it.copy(showSystemApps = newValue) }
    }

    fun loadAppList(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (_uiState.value.hasLoaded && !force) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            // 未捕获异常 = 协程崩溃 = 闪退：整段 runCatching，失败留在 uiState.error 上。
            runCatching {
                val apps = withContext(Dispatchers.IO) { fetchApps() }
                _uiState.update {
                    it.copy(apps = apps, isRefreshing = false, hasLoaded = true)
                }
            }.onFailure { e ->
                Log.e(TAG, "loadAppList failed", e)
                _uiState.update { it.copy(isRefreshing = false, hasLoaded = true, error = e) }
            }
        }
    }

    private fun fetchApps(): List<AppInfo> {
        val pm = ksuApp.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

        return packages.asSequence()
            .filter { it.packageName != ksuApp.packageName }
            .mapNotNull { pkg ->
                // applicationInfo 是平台类型、会被系统裁剪，!! 一碰就 NPE；兜底跳过这一条。
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                runCatching {
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = pkg,
                        profile = Natives.getAppProfile(pkg.packageName, appInfo.uid),
                    )
                }.getOrNull()
            }
            .sortedWith(appComparator())
            .toList()
    }

    /** 切换某个应用的 root 授权（setAppProfile 直通内核） */
    fun toggleAllowSu(app: AppInfo, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                val profile = Natives.getAppProfile(app.profileKey, app.uid)
                    ?: Natives.Profile(app.profileKey)
                Natives.setAppProfile(
                    profile.copy(
                        name = app.profileKey,
                        currentUid = app.uid,
                        allowSu = allow,
                        // 授权时用默认 root 配置；取消授权时也复位成默认非 root 配置
                        rootUseDefault = true,
                        nonRootUseDefault = true,
                    )
                )
            }.getOrDefault(false)
            if (success) {
                // 只更新这一项，避免整表重扫（PackageManager + 每个包一次 ioctl 很贵）
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.packageName == app.packageName && it.uid == app.uid) {
                                it.copy(profile = it.profile?.copy(allowSu = allow))
                            } else {
                                it
                            }
                        }.sortedWith(appComparator())
                    )
                }
            }
        }
    }

    private fun appComparator(): Comparator<AppInfo> =
        compareBy<AppInfo> { if (it.allowSu) 0 else if (it.hasCustomProfile) 1 else 2 }
            .thenBy(Collator.getInstance(Locale.getDefault())) { it.label }

    companion object {
        private const val TAG = "SuperUserViewModel"
    }
}
