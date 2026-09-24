package com.sevenk.core.ui.screen.module

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.FixedScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.R
import com.sevenk.core.data.model.Module
import com.sevenk.core.data.model.ModuleUpdateInfo
import com.sevenk.core.data.repository.isSoftRebootPreferred
import com.sevenk.core.ui.component.ObserveAsEvents
import com.sevenk.core.ui.component.ScrollToTopOnChange
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.dialog.rememberLoadingDialog
import com.sevenk.core.ui.theme.materialDialogColor
import com.sevenk.core.ui.component.material.ExpressiveDialog
import com.sevenk.core.ui.component.material.ExpressiveScaffold
import com.sevenk.core.ui.component.material.ExpressiveSwitch
import com.sevenk.core.ui.component.material.ModuleIcon
import com.sevenk.core.ui.component.material.SearchAppBar
import com.sevenk.core.ui.component.material.SnackBarHost
import com.sevenk.core.ui.component.material.TonalCard
import com.sevenk.core.ui.component.statustag.StatusTag
import com.sevenk.core.ui.util.ModuleCardArt
import com.sevenk.core.ui.util.drawCardArt
import com.sevenk.core.ui.util.reboot

@SuppressLint("StringFormatInvalid")
@Composable
fun ModulePagerMaterial(
    uiState: ModuleUiState,
    confirmDialogState: ModuleConfirmDialogState?,
    moduleEvent: Flow<ModuleEffect>,
    actions: ModuleActions,
    bottomInnerPadding: Dp,
) {
    val snackBarHost = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val resource = LocalResources.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val pullToRefreshState = rememberPullToRefreshState()

    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val refreshTick = remember { mutableIntStateOf(0) }
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val fabExpanded by remember {
        var lastIndex = 0
        var lastOffset = 0
        var scrollDelta = 0f
        var expanded = true
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val delta = if (currentIndex == lastIndex) {
                (currentOffset - lastOffset).toFloat()
            } else if (currentIndex > lastIndex) {
                100f
            } else {
                -100f
            }
            scrollDelta = (scrollDelta + delta).coerceIn(-threshold, threshold)
            lastIndex = currentIndex
            lastOffset = currentOffset
            if (currentIndex == 0) {
                expanded = true
                scrollDelta = 0f
            } else if (expanded && scrollDelta >= threshold) {
                expanded = false
                scrollDelta = 0f
            } else if (!expanded && scrollDelta <= -threshold) {
                expanded = true
                scrollDelta = 0f
            }
            expanded
        }
    }

    val shortcutState = rememberModuleShortcutState(context)
    val showShortcutDialog = remember { mutableStateOf(false) }
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            when (val request = confirmDialogState?.request) {
                is ModuleConfirmRequest.Uninstall -> actions.onUninstallModule(request.module)
                is ModuleConfirmRequest.Update -> actions.onConfirmUpdate(request)
                null -> Unit
            }
        },
        onDismiss = actions.onDismissConfirmRequest,
    )

    fun openShortcutDialogForType(type: ShortcutType) {
        shortcutState.selectType(type)
        showShortcutDialog.value = true
    }

    val pickShortcutIconLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        shortcutState.updateIconUri(uri?.toString())
    }

    fun onModuleAddShortcut(module: Module, type: ShortcutType) {
        shortcutState.bindModule(module)
        openShortcutDialogForType(type)
    }

    LaunchedEffect(confirmDialogState) {
        confirmDialogState?.let {
            confirmDialog.showConfirm(
                title = it.title,
                content = it.content,
                markdown = it.markdown,
                html = it.html,
                confirm = it.confirm,
                dismiss = it.dismiss,
            )
        }
    }

    val scope = rememberCoroutineScope()
    val snackbarJob = remember { mutableStateOf<Job?>(null) }
    ObserveAsEvents(moduleEvent) { event ->
        when (event) {
            is ModuleEffect.Toast -> {
                Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }

            is ModuleEffect.SnackBar -> {
                // Cancel the previous reboot snackbar so a new one replaces it instead of queueing
                snackbarJob.value?.cancel()
                snackBarHost.currentSnackbarData?.dismiss()
                // Soft reboot keeps the jailbreak and still applies module changes
                val softReboot = isSoftRebootPreferred()
                snackbarJob.value = scope.launch {
                    val result = snackBarHost.showSnackbar(
                        message = event.message,
                        actionLabel = resource.getString(if (softReboot) R.string.reboot_soft else R.string.reboot),
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        reboot(if (softReboot) "soft_reboot" else "")
                    }
                }
            }
        }
    }

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.module)) },
                searchText = uiState.searchStatus.searchText,
                onSearchTextChange = actions.onSearchTextChange,
                onClearClick = actions.onClearSearch,
                snackbarHostState = snackBarHost,
                navigationIcon = {
                    IconButton(
                        onClick = { actions.onOpenRepo() }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cloud,
                            contentDescription = stringResource(id = R.string.module_repos)
                        )
                    }
                },
                actions = {
                    var showDropdown by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showDropdown = true }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(id = R.string.settings)
                        )
                        DropdownMenuPopup(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
                        ) {
                            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                                CheckableDropdownMenuItem(
                                    text = { Text(stringResource(R.string.module_sort_action_first)) },
                                    checked = uiState.sortActionFirst,
                                    checkedLeadingIcon = {
                                        Icon(
                                            Icons.Filled.Check,
                                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                            contentDescription = null,
                                        )
                                    },
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        actions.onToggleSortActionFirst()
                                    },
                                    shapes = MenuDefaults.itemShape(index = 0, count = 2),
                                )
                                CheckableDropdownMenuItem(
                                    text = { Text(stringResource(R.string.module_sort_enabled_first)) },
                                    checked = uiState.sortEnabledFirst,
                                    checkedLeadingIcon = {
                                        Icon(
                                            Icons.Filled.Check,
                                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                            contentDescription = null,
                                        )
                                    },
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        actions.onToggleSortEnabledFirst()
                                    },
                                    shapes = MenuDefaults.itemShape(index = 1, count = 2),
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                searchContent = { bottomPadding, closeSearch ->
                    val latestSearchResults = rememberUpdatedState(uiState.searchResults)
                    ScrollToTopOnChange(
                        searchListState,
                        uiState.searchStatus.searchText,
                    ) { latestSearchResults.value }
                    ModuleList(
                        bottomInnerPadding = bottomPadding,
                        modifier = Modifier.fillMaxSize(),
                        listState = searchListState,
                        displayModules = uiState.searchResults,
                        updateInfoMap = uiState.updateInfo,
                        actions = actions,
                        onClickModule = { module ->
                            if (module.hasWebUi) {
                                actions.onOpenWebUi(module)
                                closeSearch()
                            }
                        },
                        onModuleAddShortcut = { module, type -> onModuleAddShortcut(module, type) },
                        closeSearch = closeSearch,
                    )
                }
            )
        },
        floatingActionButton = {
            if (uiState.installButtonVisible) {
                val moduleInstall = stringResource(id = R.string.module_install)
                val selectZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { activityResult ->
                    if (activityResult.resultCode != RESULT_OK) {
                        return@rememberLauncherForActivityResult
                    }
                    val data = activityResult.data ?: return@rememberLauncherForActivityResult
                    val clipData = data.clipData

                    val uris = mutableListOf<Uri>()
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                        }
                    } else {
                        data.data?.let { uris.add(it) }
                    }

                    actions.onOpenFlash(uris)
                }

                SmallExtendedFloatingActionButton(
                    modifier = Modifier.padding(bottom = bottomInnerPadding),
                    expanded = fabExpanded,
                    onClick = {
                        // Select the zip files to install
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        selectZipLauncher.launch(intent)
                    },
                    icon = { Icon(Icons.Filled.Add, moduleInstall) },
                    text = { Text(text = moduleInstall) },
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = {
            SnackBarHost(hostState = snackBarHost, modifier = Modifier.let {
                if (!uiState.installButtonVisible) it.padding(
                    bottom =
                        bottomInnerPadding
                ) else it
            })
        }
    ) { innerPadding ->
        PullToRefreshBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            isRefreshing = uiState.isRefreshing,
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                actions.onRefresh()
                refreshTick.intValue++
            },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = uiState.isRefreshing,
                    state = pullToRefreshState,
                )
            },
        ) {
            if (uiState.magiskInstalled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.module_magisk_conflict),
                        textAlign = TextAlign.Center,
                    )
                }
                return@PullToRefreshBox
            }
            val latestModuleList = rememberUpdatedState(uiState.moduleList)
            val latestRefreshing = rememberUpdatedState(uiState.isRefreshing)
            ScrollToTopOnChange(
                listState,
                uiState.sortEnabledFirst,
                uiState.sortActionFirst,
                refreshTick.intValue,
                isBusy = { latestRefreshing.value },
            ) { latestModuleList.value }
            ModuleList(
                bottomInnerPadding = bottomInnerPadding,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                listState = listState,
                displayModules = uiState.moduleList,
                updateInfoMap = uiState.updateInfo,
                actions = actions,
                onClickModule = { module ->
                    if (module.hasWebUi) {
                        actions.onOpenWebUi(module)
                    }
                },
                onModuleAddShortcut = { module, type -> onModuleAddShortcut(module, type) },
            )
        }
    }

    ModuleShortcutSheet(
        show = showShortcutDialog.value,
        shortcutState = shortcutState,
        onDismiss = { showShortcutDialog.value = false },
        onPickShortcutIcon = { pickShortcutIconLauncher.launch("image/*") },
        onDeleteShortcut = {
            shortcutState.deleteShortcut(context)
            showShortcutDialog.value = false
        },
        onConfirmShortcut = {
            // 🔴（v2.19）createShortcut 现在要跑 root 命令：挪进 IO 协程，别堵主线程（onClick 非 @Composable）
            scope.launch { shortcutState.createShortcut(context) }
            showShortcutDialog.value = false
        },
    )
}

@Composable
private fun ModuleList(
    bottomInnerPadding: Dp,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    displayModules: List<Module>,
    updateInfoMap: Map<String, ModuleUpdateInfo>,
    actions: ModuleActions,
    onClickModule: (Module) -> Unit,
    onModuleAddShortcut: (Module, ShortcutType) -> Unit,
    closeSearch: () -> Unit? = {},
) {
    val loadingDialog = rememberLoadingDialog()
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp + bottomInnerPadding + 56.dp + 16.dp
        ),
    ) {
        items(displayModules, key = { it.id }, contentType = { "module" }) { module ->
            val scope = rememberCoroutineScope()
            val moduleUpdateInfo = updateInfoMap[module.id] ?: ModuleUpdateInfo.Empty

            ModuleItem(
                module = module,
                updateUrl = moduleUpdateInfo.downloadUrl,
                onUninstallClicked = {
                    if (module.remove) {
                        actions.onUndoUninstallModule(module)
                    } else {
                        actions.onRequestUninstallConfirmation(module)
                    }
                },
                onCheckChanged = {
                    actions.onToggleModule(module)
                },
                onUpdate = {
                    scope.launch {
                        loadingDialog.withLoading {
                            actions.onRequestUpdateConfirmation(module, moduleUpdateInfo)
                        }
                    }
                },
                onAddShortcut = { type -> onModuleAddShortcut(module, type) },
                onClick = { onClickModule(module) },
                onExecuteAction = { actions.onExecuteModuleAction(module) },
                closeSearch = { closeSearch() }
            )
        }
    }
}

@Composable
private fun ModuleShortcutSheet(
    show: Boolean,
    shortcutState: ModuleShortcutState,
    onDismiss: () -> Unit,
    onPickShortcutIcon: () -> Unit,
    onDeleteShortcut: () -> Unit,
    onConfirmShortcut: () -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val resources = LocalResources.current

    fun copyShortcutUrl() {
        val url = shortcutState.buildShortcutUrl() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("KernelSU deep link", url))
        Toast.makeText(context, resources.getString(R.string.module_shortcut_scheme_copied), Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.module_shortcut_title),
                style = MaterialTheme.typography.titleLarge
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = 13.dp)
                    .size(100.dp)
                    .clip(RoundedCornerShape(25.dp))
            ) {
                val preview = shortcutState.previewIcon
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        modifier = Modifier.size(100.dp),
                        contentDescription = null,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color.White)
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        contentScale = FixedScale(1.5f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onPickShortcutIcon,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        text = stringResource(id = R.string.module_shortcut_icon_pick)
                    )
                }
                AnimatedVisibility(
                    visible = shortcutState.iconUri != shortcutState.defaultShortcutIconUri,
                    enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                    exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
                ) {
                    IconButton(
                        onClick = shortcutState::resetIconToDefault,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = shortcutState.name,
                onValueChange = shortcutState::updateName,
                label = { Text(stringResource(id = R.string.module_shortcut_name_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )
            if (shortcutState.hasExistingShortcut) {
                TextButton(
                    onClick = onDeleteShortcut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                ) {
                    Text(stringResource(id = R.string.module_shortcut_delete))
                }
            }
            TextButton(
                onClick = ::copyShortcutUrl,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            ) {
                Text(stringResource(id = R.string.module_shortcut_copy_scheme))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(id = android.R.string.cancel))
                }
                Button(
                    onClick = onConfirmShortcut,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (shortcutState.hasExistingShortcut) {
                            stringResource(id = R.string.module_update)
                        } else {
                            stringResource(id = android.R.string.ok)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleItem(
    module: Module,
    updateUrl: String,
    onUninstallClicked: () -> Unit,
    onCheckChanged: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
    onClick: () -> Unit,
    onExecuteAction: () -> Unit,
    closeSearch: () -> Unit
) {
    // ── 「卡面」:这个模块自己的卡片背景图(与 Miuix 界面共用同一套存储) ──
    // 与 Miuix 侧同一个道理:图必须画在 Card **外层**的 drawWithContent 里,
    // 并且把卡片底色改成透明 —— Card 的底色是在 drawContent() 里画的,会盖掉图。
    val context = LocalContext.current
    val cardArt by produceState<ImageBitmap?>(null, context, module.id, ModuleCardArt.version) {
        value = withContext(Dispatchers.IO) { ModuleCardArt.load(context, module.id, 1024) }
    }
    var showCardArtDialog by remember { mutableStateOf(false) }
    // 卡片真实像素尺寸:自裁取景框就按这个比例开,做到所见即所得
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val art = cardArt
    val cardShape = MaterialTheme.shapes.large
    val cardContainer = MaterialTheme.colorScheme.surfaceBright
    // 颜色在 Composable 作用域里先取好:不能在 drawWithContent 的 lambda 里读主题
    val cardArtScrim = cardContainer.copy(alpha = ModuleCardArt.SCRIM)
    val cardArtModifier = if (art != null) {
        Modifier
            .clip(cardShape)
            .drawWithContent {
                drawCardArt(art, size, cardArtScrim)
                drawContent()
            }
    } else {
        Modifier
    }

    TonalCard(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { cardSize = it }
            .then(cardArtModifier),
        // 有卡面时底色必须透明,否则会把图盖掉
        containerColor = if (art != null) Color.Transparent else cardContainer,
        contentColor = MaterialTheme.colorScheme.contentColorFor(cardContainer),
    ) {
        val haptic = LocalHapticFeedback.current
        val textDecoration = if (!module.remove) null else TextDecoration.LineThrough
        val interactionSource = remember { MutableInteractionSource() }
        val indication = LocalIndication.current
        var expanded by rememberSaveable(module.id) { mutableStateOf(false) }
        var isOverflowing by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .run {
                    if (module.hasWebUi) {
                        toggleable(
                            value = module.enabled,
                            enabled = !module.remove && module.enabled,
                            interactionSource = interactionSource,
                            role = Role.Button,
                            indication = indication,
                            onValueChange = { onClick() }
                        )
                    } else {
                        this
                    }
                }
                .padding(16.dp, 14.dp, 16.dp, 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val moduleVersion = stringResource(id = R.string.module_version)
                val moduleAuthor = stringResource(id = R.string.module_author)

                Column(
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    // 模块图标:默认显示首字色块,点一下可以换图/自裁。
                    // 放在这个 Column 内部而不是外层 Row 的开头 —— 外层那个 Row 是
                    // "fillMaxWidth(0.8f) 的 Column + Spacer + fillMaxWidth() 的开关排" 的
                    // 剩余空间分配,再插一个 46dp 固定宽子项会把右侧开关那排挤成 0 宽。
                    // 放这里外层测量结果完全不变,只有文字可用宽度少 56dp。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModuleIcon(
                            moduleId = module.id,
                            moduleName = module.name,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = module.name,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                                textDecoration = textDecoration,
                            )

                            Text(
                                text = "$moduleVersion: ${module.version}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = textDecoration
                            )

                            Text(
                                text = "$moduleAuthor: ${module.author}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = textDecoration
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ExpressiveSwitch(
                        enabled = !module.update,
                        checked = module.enabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onCheckChanged(it)
                        },
                        interactionSource = if (!module.hasWebUi) interactionSource else remember { MutableInteractionSource() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                modifier = Modifier
                    .animateContentSize(
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing
                        )
                    )
                    .then(
                        if (isOverflowing || expanded) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
                text = module.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                textDecoration = textDecoration,
                onTextLayout = { textLayoutResult ->
                    isOverflowing = if (expanded) {
                        textLayoutResult.lineCount > 4
                    } else {
                        textLayoutResult.hasVisualOverflow
                    }
                }
            )

            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                if (module.metamodule) {
                    StatusTag(
                        "META",
                        modifier = Modifier.padding(bottom = 4.dp),
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        backgroundColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(thickness = Dp.Hairline)

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hasUpdate = updateUrl.isNotEmpty()
                val actionButtonsEnabled = !module.remove && module.enabled

                AnimatedVisibility(
                    visible = actionButtonsEnabled,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (module.hasActionScript) {
                            CombinedClickableButton(
                                onClick = {
                                    onExecuteAction()
                                    closeSearch()
                                },
                                onLongClick = { onAddShortcut(ShortcutType.Action) },
                                modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                                shape = ButtonDefaults.filledTonalShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = null
                                )
                                if (!module.hasWebUi && !hasUpdate) {
                                    Text(
                                        modifier = Modifier.padding(start = 7.dp),
                                        text = stringResource(R.string.action),
                                        fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                                        fontSize = MaterialTheme.typography.labelMedium.fontSize
                                    )
                                }
                            }
                        }

                        if (module.hasWebUi) {
                            CombinedClickableButton(
                                onClick = {
                                    onClick()
                                    closeSearch()
                                },
                                onLongClick = { onAddShortcut(ShortcutType.WebUI) },
                                modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                                shape = ButtonDefaults.filledTonalShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = Icons.Outlined.Code,
                                    contentDescription = null
                                )
                                if (!module.hasActionScript && !hasUpdate) {
                                    Text(
                                        modifier = Modifier.padding(start = 7.dp),
                                        fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                        text = stringResource(R.string.open)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f, true))

                AnimatedVisibility(
                    visible = hasUpdate,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row {
                        Button(
                            modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                            enabled = !module.remove,
                            onClick = onUpdate,
                            shape = ButtonDefaults.textShape,
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Icon(
                                modifier = Modifier.size(20.dp),
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null
                            )
                            if (!module.hasActionScript || !module.hasWebUi) {
                                Text(
                                    modifier = Modifier.padding(start = 7.dp),
                                    fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    text = stringResource(R.string.module_update)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))
                    }
                }

                // 「卡面」:给这个模块的卡片换背景图。放在卸载左边,与 Miuix 界面一致。
                FilledTonalButton(
                    modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                    onClick = { showCardArtDialog = true },
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Outlined.Wallpaper,
                        contentDescription = "卡面",
                    )
                }

                Spacer(Modifier.width(12.dp))

                FilledTonalButton(
                    modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                    onClick = onUninstallClicked,
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    if (!module.remove) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                        )
                    } else {
                        Icon(
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(180f),
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                        )
                    }
                    if (!module.hasActionScript && !module.hasWebUi || !hasUpdate) {
                        Text(
                            modifier = Modifier.padding(start = 7.dp),
                            fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            text = stringResource(if (module.remove) R.string.undo else R.string.uninstall)
                        )
                    }
                }
            }
        }
    }

    if (showCardArtDialog) {
        // 取景框比例 = 卡片真实宽/高(卡片高度会随描述展开变化,取点开这一刻的值)
        val cardAspect = if (cardSize.height > 0) {
            cardSize.width.toFloat() / cardSize.height
        } else {
            2.2f
        }
        ModuleCardArtDialog(
            moduleId = module.id,
            moduleName = module.name,
            aspect = cardAspect,
            onDismiss = { showCardArtDialog = false },
        )
    }
}

/** 「卡面」设置框(Material 版):选图(并自裁) / 重新自裁 / 移除背景图 / 取消 */
@Composable
private fun ModuleCardArtDialog(
    moduleId: String,
    moduleName: String,
    aspect: Float,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var cropping by remember { mutableStateOf(false) }
    val hasArt = remember(moduleId, ModuleCardArt.version) { ModuleCardArt.has(context, moduleId) }
    val hasOriginal = remember(moduleId, ModuleCardArt.version) { ModuleCardArt.hasOriginal(context, moduleId) }
    val a = aspect.coerceIn(0.5f, 4f)

    // 取景参数提升到这一层:ExpressiveDialog 的 text 与 confirmButton 是两个**平级槽位**,
    // 平级之间没法直接传状态,只能由共同父级持有(和 material/ModuleIcon.kt 同一做法)。
    val saved = remember(moduleId) { ModuleCardArt.loadCrop(context, moduleId) }
    var biasX by remember(moduleId) { mutableFloatStateOf(saved?.biasX ?: 0f) }
    var biasY by remember(moduleId) { mutableFloatStateOf(saved?.biasY ?: 0f) }
    var zoom by remember(moduleId) { mutableFloatStateOf(saved?.zoom ?: 1f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (ModuleCardArt.saveOriginal(context, moduleId, uri)) {
                // 换了新图:取景参数复位,免得套用上一张图的框
                biasX = 0f
                biasY = 0f
                zoom = 1f
                cropping = true
            } else {
                Toast.makeText(context, "这张图读不出来,换一张试试", Toast.LENGTH_LONG).show()
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        containerColor = materialDialogColor(),
        title = { Text(if (cropping) "自裁卡面" else "卡面") },
        text = {
            if (cropping) {
                ModuleCardArtCropContent(
                    moduleId = moduleId,
                    aspect = a,
                    biasX = biasX,
                    biasY = biasY,
                    zoom = zoom,
                    onTransform = { dx, dy, dz ->
                        biasX = (biasX + dx).coerceIn(-1f, 1f)
                        biasY = (biasY + dy).coerceIn(-1f, 1f)
                        zoom = (zoom * dz).coerceIn(1f, 4f)
                    },
                    onZoomStep = { delta -> zoom = (zoom + delta).coerceIn(1f, 4f) },
                    onReset = {
                        biasX = 0f
                        biasY = 0f
                        zoom = 1f
                    },
                )
            } else {
                Column {
                    Text(
                        text = "给「$moduleName」这张卡片换一张背景图;选完可以拖动/双指缩放自裁。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasOriginal) {
                        TextButton(
                            onClick = { cropping = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("自裁这张图")
                        }
                    }
                    if (hasArt) {
                        TextButton(
                            onClick = {
                                ModuleCardArt.clear(context, moduleId)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("移除背景图")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (cropping) {
                TextButton(
                    onClick = {
                        if (ModuleCardArt.bake(context, moduleId, a, biasX, biasY, zoom)) {
                            onDismiss()
                        } else {
                            Toast.makeText(context, "保存失败,重新选一张图试试", Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text("确定")
                }
            } else {
                TextButton(onClick = { picker.launch("image/*") }) {
                    Text(if (hasArt) "换一张图片(并自裁)" else "选一张图片(并自裁)")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 自裁卡面:取景框比例 = **卡片真实比例**(aspect),所以所见即所得。
 *
 * 无状态:取景参数由 [ModuleCardArtDialog] 持有并通过回调更新。
 * 尺寸按屏幕算并封顶 —— 弹窗的内容槽位高度有限,预览太大就把下面那排
 * 「缩小/放大/复位」顶出可见区域了(和 Miuix 侧同一个毛病)。
 *
 * @param onTransform 拖动/双指缩放回调,参数是**已归一化**的位移(-1~1)与缩放倍数
 */
@Composable
private fun ModuleCardArtCropContent(
    moduleId: String,
    aspect: Float,
    biasX: Float,
    biasY: Float,
    zoom: Float,
    onTransform: (dx: Float, dy: Float, dz: Float) -> Unit,
    onZoomStep: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleCardArt.version) {
        value = withContext(Dispatchers.IO) { ModuleCardArt.loadOriginal(context, moduleId) }
    }
    val a = aspect.coerceIn(0.5f, 4f)
    val screen = LocalConfiguration.current
    val previewW = minOf(
        screen.screenWidthDp.dp * 0.78f,
        screen.screenHeightDp.dp * 0.45f * a,
        420.dp,
    ).coerceAtLeast(140.dp)
    val previewH = (previewW / a).coerceAtLeast(72.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(previewW, previewH)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        onTransform(-pan.x / w * 2f, -pan.y / h * 2f, gestureZoom)
                    }
                },
        ) {
            val b = bitmap
            if (b != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = ModuleCardArt.cropRect(b.width, b.height, a, biasX, biasY, zoom)
                    drawImage(
                        image = b,
                        srcOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                        srcSize = IntSize(
                            r.width().toInt().coerceAtLeast(1),
                            r.height().toInt().coerceAtLeast(1),
                        ),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        Text(
            text = "拖动调位置 · 双指缩放 · 现在是 ${"%.1f".format(zoom)}×",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onZoomStep(-0.25f) },
                modifier = Modifier.weight(1f),
                enabled = zoom > 1f,
            ) {
                Text("缩小")
            }
            TextButton(
                onClick = { onZoomStep(0.25f) },
                modifier = Modifier.weight(1f),
                enabled = zoom < 4f,
            ) {
                Text("放大")
            }
            TextButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
            ) {
                Text("复位")
            }
        }
    }
}

@Composable
fun CombinedClickableButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .semantics { role = Role.Button }
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = border,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                Modifier
                    .defaultMinSize(
                        minWidth = ButtonDefaults.MinWidth,
                        minHeight = ButtonDefaults.MinHeight,
                    )
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
