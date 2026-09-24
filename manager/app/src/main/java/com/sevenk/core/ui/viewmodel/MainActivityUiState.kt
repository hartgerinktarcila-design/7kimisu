package com.sevenk.core.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    val snowEnabled: Boolean,
    val snowSize: Float,
    val snowSpeed: Float,
    val trollRainEnabled: Boolean,
    val trollRainSize: Float,
    val trollRainSpeed: Float,
    val gravityEnabled: Boolean,
    val gravityMode: Int,
    val uiMode: UiMode,
)
