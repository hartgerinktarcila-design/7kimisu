package com.sevenk.core.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.sevenk.core.data.repository.SettingsRepository
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.theme.ThemeController

class MainActivityViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val prefs = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val settingRepo: SettingsRepository = SettingsRepositoryImpl()
    private val mainPageState = MainPageState(savedStateHandle)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in observedKeys) {
            _uiState.value = readUiState()
        }
    }

    private val _uiState = MutableStateFlow(readUiState())
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()
    val selectedMainPage: StateFlow<Int> = mainPageState.selectedPage

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun setSelectedMainPage(page: Int) {
        mainPageState.updateSelectedPage(page)
    }

    private fun readUiState(): MainActivityUiState {
        return MainActivityUiState(
            appSettings = ThemeController.getAppSettings(),
            pageScale = settingRepo.pageScale,
            enableBlur = settingRepo.enableBlur,
            enableFloatingBottomBar = settingRepo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = settingRepo.enableFloatingBottomBarBlur,
            enableNavigationBadge = settingRepo.enableNavigationBadge,
            snowEnabled = settingRepo.snowEnabled,
            snowSize = settingRepo.snowSize,
            snowSpeed = settingRepo.snowSpeed,
            trollRainEnabled = settingRepo.trollRainEnabled,
            trollRainSize = settingRepo.trollRainSize,
            trollRainSpeed = settingRepo.trollRainSpeed,
            gravityEnabled = settingRepo.gravityEnabled,
            gravityMode = settingRepo.gravityMode,
            uiMode = UiMode.fromValue(settingRepo.uiMode),
        )
    }

    private companion object {
        val observedKeys = setOf(
            "color_mode",
            "key_color",
            "color_style",
            "color_spec",
            "page_scale",
            "enable_blur",
            "enable_floating_bottom_bar",
            "enable_floating_bottom_bar_blur",
            "enable_navigation_badge",
            "snow_enabled",
            "snow_size",
            "snow_speed",
            "troll_rain_enabled",
            "troll_rain_size",
            "troll_rain_speed",
            "gravity_enabled",
            "gravity_mode",
            "ui_mode",
            // 个性化壁纸(改了要立刻重新组合,壁纸才会即时生效)
            "wallpaper_path",
            "wallpaper_kind",
            "wallpaper_dim",
            "wallpaper_blur",
            "ui_translucent",
            "ui_translucent_alpha",
            "wallpaper_seed",
            "wallpaper_seed_color",
        )
    }
}

private const val SELECTED_MAIN_PAGE_KEY = "selected_main_page"

private class MainPageState(
    private val savedStateHandle: SavedStateHandle,
) {
    val selectedPage: StateFlow<Int> = savedStateHandle.getStateFlow(SELECTED_MAIN_PAGE_KEY, 0)

    fun updateSelectedPage(page: Int) {
        savedStateHandle[SELECTED_MAIN_PAGE_KEY] = MainPagerConfig.coercePage(page)
    }
}

object MainPagerConfig {
    const val PAGE_COUNT = 4
    const val LAST_PAGE_INDEX = PAGE_COUNT - 1

    fun coercePage(page: Int): Int = page.coerceIn(0, LAST_PAGE_INDEX)
}
