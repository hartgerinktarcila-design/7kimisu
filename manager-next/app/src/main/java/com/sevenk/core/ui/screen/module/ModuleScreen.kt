package com.sevenk.core.ui.screen.module

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sevenk.core.R
import com.sevenk.core.data.model.Module
import com.sevenk.core.ui.component.ConfirmDialog
import com.sevenk.core.ui.navigation.LocalNavigator
import com.sevenk.core.ui.navigation.Route
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
fun ModuleScreen(
    bottomPadding: Dp = 0.dp,
    viewModel: ModuleViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchModuleList()
    }

    // 模块 zip 安装入口（文件选择器）
    val pickModuleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            navigator.push(Route.FlashModule(zipUri = uri.toString()))
        }
    }

    val toggleFailEnable = stringResource(R.string.module_failed_to_enable)
    val toggleFailDisable = stringResource(R.string.module_failed_to_disable)
    val uninstallSuccess = stringResource(R.string.module_uninstall_success)
    val uninstallFailed = stringResource(R.string.module_uninstall_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.module),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            pickModuleLauncher.launch(
                                Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "application/zip"
                                }
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            tint = colorScheme.onSurface,
                            contentDescription = stringResource(R.string.module_install),
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
            onRefresh = { viewModel.fetchModuleList() },
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
                items(uiState.modules, key = { it.id }) { module ->
                    ModuleItem(
                        module = module,
                        onCheckChanged = { _ ->
                            viewModel.toggleModule(module) { success ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        (if (module.enabled) toggleFailDisable else toggleFailEnable)
                                            .format(module.name),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onUninstall = { viewModel.requestUninstall(module) },
                        onUndoUninstall = {
                            viewModel.undoUninstall(module) { }
                        },
                    )
                }
                if (uiState.hasLoaded && uiState.modules.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.module_empty),
                            modifier = Modifier.padding(16.dp),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                item { Spacer(Modifier.height(bottomPadding)) }
            }
        }
    }

    // 卸载确认框
    val pending = uiState.pendingUninstall
    ConfirmDialog(
        show = pending != null,
        title = stringResource(R.string.module),
        content = stringResource(R.string.module_uninstall_confirm).format(pending?.name ?: ""),
        confirm = stringResource(R.string.uninstall),
        dismiss = stringResource(R.string.cancel),
        onConfirm = {
            val name = pending?.name ?: ""
            viewModel.confirmUninstall { success ->
                Toast.makeText(
                    context,
                    (if (success) uninstallSuccess else uninstallFailed).format(name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onDismiss = { viewModel.dismissUninstall() },
    )
}

@Composable
private fun ModuleItem(
    module: Module,
    onCheckChanged: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUndoUninstall: () -> Unit,
) {
    val textDecoration = if (module.remove) TextDecoration.LineThrough else null

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = module.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurface,
                        textDecoration = textDecoration,
                    )
                    if (module.metamodule) {
                        Text(
                            text = "META",
                            fontSize = 11.sp,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.module_version) + ": " + module.version,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                    color = colorScheme.onSurfaceVariantSummary,
                    textDecoration = textDecoration,
                )
                Text(
                    text = stringResource(R.string.module_author) + ": " + module.author,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 1.dp),
                    color = colorScheme.onSurfaceVariantSummary,
                    textDecoration = textDecoration,
                )
            }
            Switch(
                enabled = !module.update,
                checked = module.enabled,
                onCheckedChange = onCheckChanged,
            )
        }

        if (module.description.isNotBlank()) {
            Text(
                text = module.description,
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
                textDecoration = textDecoration,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            IconButton(
                minHeight = 35.dp,
                minWidth = 35.dp,
                onClick = if (module.remove) onUndoUninstall else onUninstall,
                backgroundColor = colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = if (module.remove) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Delete,
                    tint = colorScheme.onSurface.copy(alpha = 0.8f),
                    contentDescription = if (module.remove) {
                        stringResource(R.string.module_undo_uninstall)
                    } else {
                        stringResource(R.string.uninstall)
                    },
                )
            }
        }
    }
}
