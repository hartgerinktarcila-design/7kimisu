package com.sevenk.core.ui.screen.superuser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sevenk.core.R
import com.sevenk.core.data.model.AppInfo
import com.sevenk.core.ui.component.AppIconImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SuperUserScreen(
    bottomPadding: Dp = 0.dp,
    viewModel: SuperUserViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    // 进这一页才加载（启动路径不碰 root shell / ioctl）
    LaunchedEffect(Unit) {
        viewModel.loadAppList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.superuser),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { viewModel.toggleShowSystemApps() }) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            tint = if (uiState.showSystemApps) {
                                colorScheme.primary
                            } else {
                                colorScheme.onSurface
                            },
                            contentDescription = stringResource(R.string.show_system_apps),
                        )
                    }
                },
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        PullToRefresh(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.loadAppList(force = true) },
            contentPadding = innerPadding,
            topAppBarScrollBehavior = scrollBehavior,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = innerPadding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                items(uiState.visibleApps, key = { "${it.uid}:${it.packageName}" }) { app ->
                    AppItem(
                        app = app,
                        onToggle = { allow -> viewModel.toggleAllowSu(app, allow) },
                    )
                }
                if (uiState.hasLoaded && uiState.visibleApps.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.superuser_empty),
                            modifier = Modifier.padding(16.dp),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                item { Spacer(Modifier.height(bottomPadding)) }
            }
        }
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(
                packageInfo = app.packageInfo,
                label = app.label,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (app.allowSu) {
                        Text(
                            text = "ROOT",
                            fontSize = 11.sp,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Switch(
                checked = app.allowSu,
                onCheckedChange = onToggle,
            )
        }
    }
}
