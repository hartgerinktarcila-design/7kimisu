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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FixedScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.sevenk.core.R
import com.sevenk.core.data.model.Module
import com.sevenk.core.data.model.ModuleUpdateInfo
import com.sevenk.core.data.repository.isSoftRebootPreferred
import com.sevenk.core.ui.component.ListPopupDefaults
import com.sevenk.core.ui.component.ObserveAsEvents
import com.sevenk.core.ui.component.ScrollToTopOnChange
import com.sevenk.core.ui.component.SearchStatus
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.component.dialog.rememberLoadingDialog
import com.sevenk.core.ui.component.miuix.SearchBarFake
import com.sevenk.core.ui.component.miuix.SearchBox
import com.sevenk.core.ui.component.miuix.SearchPager
import com.sevenk.core.ui.theme.LocalEnableBlur
import com.sevenk.core.ui.theme.isInDarkTheme
import com.sevenk.core.ui.theme.miuixDialogColor
import com.sevenk.core.ui.util.BlurredBar
import com.sevenk.core.ui.util.miuixBarColor
import com.sevenk.core.ui.util.getFileName
import com.sevenk.core.ui.util.reboot
import com.sevenk.core.ui.util.rememberBlurBackdrop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sevenk.core.ui.util.ModuleCardArt
import com.sevenk.core.ui.util.drawCardArt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingActionButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@SuppressLint("StringFormatInvalid", "LocalContextGetResourceValueCall")
@Composable
fun ModulePagerMiuix(
    uiState: ModuleUiState,
    confirmDialogState: ModuleConfirmDialogState?,
    moduleEvent: Flow<ModuleEffect>,
    actions: ModuleActions,
    bottomInnerPadding: Dp,
) {
    val modules = uiState.moduleList
    val searchStatus = uiState.searchStatus

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current
    val enableBlur = LocalEnableBlur.current

    val installPromptWithName = stringResource(R.string.module_install_prompt_with_name, "%s")
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            when (val request = confirmDialogState?.request) {
                is ModuleConfirmRequest.Uninstall -> {
                    actions.onUninstallModule(request.module)
                }

                is ModuleConfirmRequest.Update -> {
                    actions.onConfirmUpdate(request)
                }

                null -> Unit
            }
        },
        onDismiss = actions.onDismissConfirmRequest,
    )

    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    val shortcutState = rememberModuleShortcutState(context)
    val showShortcutDialog = remember { mutableStateOf(false) }

    val pickShortcutIconLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        shortcutState.updateIconUri(uri?.toString())
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
                snackbarHostState.newestSnackbarData()?.dismiss()
                // Soft reboot keeps the jailbreak and still applies module changes
                val softReboot = isSoftRebootPreferred()
                snackbarJob.value = scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = context.getString(if (softReboot) R.string.reboot_soft else R.string.reboot),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        reboot(if (softReboot) "soft_reboot" else "")
                    }
                }
            }
        }
    }

    fun onModuleAddShortcut(module: Module, type: ShortcutType) {
        shortcutState.bindModule(module)
        shortcutState.selectType(type)
        showShortcutDialog.value = true
    }

    val listState = rememberLazyListState()
    val refreshTick = remember { mutableIntStateOf(0) }

    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = miuixBarColor(blurActive)

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.module),
                        actions = {
                            Box {
                                val showTopPopup = remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showTopPopup.value = true },
                                    holdDownState = showTopPopup.value
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Sort,
                                        tint = colorScheme.onSurface,
                                        contentDescription = null
                                    )
                                }
                                OverlayListPopup(
                                    show = showTopPopup.value,
                                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = {
                                        showTopPopup.value = false
                                    },
                                    content = {
                                        ListPopupColumn {
                                            DropdownImpl(
                                                text = stringResource(R.string.module_sort_action_first),
                                                optionSize = 2,
                                                isSelected = uiState.sortActionFirst,
                                                onSelectedIndexChange = {
                                                    actions.onToggleSortActionFirst()
                                                    showTopPopup.value = false
                                                },
                                                index = 0
                                            )
                                            DropdownImpl(
                                                text = stringResource(R.string.module_sort_enabled_first),
                                                optionSize = 2,
                                                isSelected = uiState.sortEnabledFirst,
                                                onSelectedIndexChange = {
                                                    actions.onToggleSortEnabledFirst()
                                                    showTopPopup.value = false
                                                },
                                                index = 1
                                            )
                                        }
                                    }
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = actions.onOpenRepo,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Download,
                                    tint = colorScheme.onSurface,
                                    contentDescription = null
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            val newOffsetY = coordinates.positionInWindow().y.toDp()
                                            if (searchStatus.offsetY != newOffsetY) {
                                                actions.onSearchStatusChange(searchStatus.copy(offsetY = newOffsetY))
                                            }
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    actions.onSearchStatusChange(searchStatus.copy(current = SearchStatus.Status.EXPANDING))
                                                }
                                            }
                                        } else Modifier,
                                    ),
                            ) {
                                SearchBarFake(searchStatus.label, dynamicTopPadding)
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            if (uiState.installButtonVisible) {
                val moduleInstall = stringResource(id = R.string.module_install)
                val confirmTitle = stringResource(R.string.module)
                var zipUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                val confirmDialog = rememberConfirmDialog(
                    onConfirm = {
                        actions.onOpenFlash(zipUris)
                    }
                )
                val selectZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { activityResult ->
                    val uris = mutableListOf<Uri>()
                    if (activityResult.resultCode != RESULT_OK) {
                        return@rememberLauncherForActivityResult
                    }
                    val data = activityResult.data ?: return@rememberLauncherForActivityResult
                    val clipData = data.clipData

                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                        }
                    } else {
                        data.data?.let { uris.add(it) }
                    }

                    if (uris.size == 1) {
                        actions.onOpenFlash(listOf(uris.first()))
                    } else if (uris.size > 1) {
                        // multiple files selected
                        zipUris = uris
                        val moduleNames = uris.mapIndexed { index, uri -> "\n${index + 1}. ${uri.getFileName(context)}" }.joinToString("")
                        val confirmContent = installPromptWithName.format(moduleNames)
                        confirmDialog.showConfirm(
                            title = confirmTitle,
                            content = confirmContent
                        )
                    }
                }
                FloatingActionButton(
                    modifier = Modifier
                        .padding(bottom = bottomInnerPadding + 20.dp, end = 20.dp)
                        .border(0.05.dp, colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                    shadowElevation = 0.dp,
                    onClick = {
                        // Select the zip files to install
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        selectZipLauncher.launch(intent)
                    },
                    content = {
                        Icon(
                            Icons.Rounded.Add,
                            moduleInstall,
                            modifier = Modifier.size(40.dp),
                            tint = colorScheme.onPrimary
                        )
                    },
                )
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = actions.onSearchStatusChange,
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
            ) {
                val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                ModuleList(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    modules = uiState.searchResults,
                    updateInfoMap = uiState.updateInfo,
                    actions = actions,
                    onModuleAddShortcut = ::onModuleAddShortcut,
                    contentPadding = PaddingValues(
                        top = 6.dp,
                        start = 0.dp,
                        end = 0.dp,
                        bottom = maxOf(bottomInnerPadding, imeBottomPadding),
                    ),
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                state = snackbarHostState,
                modifier = if (uiState.installButtonVisible) {
                    Modifier
                } else {
                    // No FAB slot to stack above: keep the snackbar clear of the main bottom bar.
                    Modifier.padding(bottom = bottomInnerPadding + 20.dp)
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        if (uiState.magiskInstalled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.module_magisk_conflict),
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        val layoutDirection = LocalLayoutDirection.current
        searchStatus.SearchBox {
            val pullToRefreshState = rememberPullToRefreshState()
            val refreshTexts = listOf(
                stringResource(R.string.refresh_pulling),
                stringResource(R.string.refresh_release),
                stringResource(R.string.refresh_refresh),
                stringResource(R.string.refresh_complete),
            )
            val contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = bottomInnerPadding + FloatingActionButtonDefaults.MinHeight + 20.dp + 12.dp,
            )
            PullToRefresh(
                isRefreshing = uiState.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = {
                    actions.onRefresh()
                    refreshTick.intValue++
                },
                refreshTexts = refreshTexts,
                contentPadding = contentPadding,
            ) {
                if (modules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                start = innerPadding.calculateStartPadding(layoutDirection),
                                end = innerPadding.calculateEndPadding(layoutDirection),
                                bottom = bottomInnerPadding
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // The refresh indicator doubles as the first-load hint;
                        // only announce emptiness once loading has finished.
                        if (uiState.hasLoaded) {
                            Text(
                                stringResource(R.string.module_empty),
                                textAlign = TextAlign.Center,
                                color = Color.Gray,
                            )
                        }
                    }
                } else {
                    val latestModules = rememberUpdatedState(modules)
                    val latestRefreshing = rememberUpdatedState(uiState.isRefreshing)
                    ScrollToTopOnChange(
                        listState,
                        uiState.sortEnabledFirst,
                        uiState.sortActionFirst,
                        refreshTick.intValue,
                        isBusy = { latestRefreshing.value },
                    ) { latestModules.value }
                    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                        ModuleList(
                            modifier = Modifier
                                .fillMaxHeight()
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            modules = modules,
                            updateInfoMap = uiState.updateInfo,
                            actions = actions,
                            onModuleAddShortcut = { module, type ->
                                onModuleAddShortcut(module, type)
                            },
                            contentPadding = contentPadding,
                            listState = listState,
                        )
                    }
                }
            }
        }
    }
    ModuleShortcutDialog(
        show = showShortcutDialog.value,
        onDismissRequest = { showShortcutDialog.value = false },
        shortcutState = shortcutState,
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
private fun ModuleShortcutDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    shortcutState: ModuleShortcutState,
    onPickShortcutIcon: () -> Unit,
    onDeleteShortcut: () -> Unit,
    onConfirmShortcut: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    fun copyShortcutUrl() {
        val url = shortcutState.buildShortcutUrl() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("KernelSU deep link", url))
        Toast.makeText(context, resources.getString(R.string.module_shortcut_scheme_copied), Toast.LENGTH_SHORT).show()
    }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.module_shortcut_title),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
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
                Row {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(id = R.string.module_shortcut_icon_pick),
                        onClick = onPickShortcutIcon,
                    )
                    AnimatedVisibility(
                        visible = shortcutState.iconUri != shortcutState.defaultShortcutIconUri,
                        enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                        exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        IconButton(
                            onClick = shortcutState::resetIconToDefault,
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Undo,
                                contentDescription = null,
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                TextField(
                    value = shortcutState.name,
                    onValueChange = shortcutState::updateName,
                    label = stringResource(id = R.string.module_shortcut_name_label)
                )
                if (shortcutState.hasExistingShortcut) {
                    TextButton(
                        text = stringResource(id = R.string.module_shortcut_delete),
                        onClick = onDeleteShortcut,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    text = stringResource(id = R.string.module_shortcut_copy_scheme),
                    onClick = ::copyShortcutUrl,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(id = android.R.string.cancel),
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = if (shortcutState.hasExistingShortcut) {
                            stringResource(id = R.string.module_update)
                        } else {
                            stringResource(id = android.R.string.ok)
                        },
                        onClick = onConfirmShortcut,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    )
}

@Composable
private fun ModuleList(
    modifier: Modifier = Modifier,
    modules: List<Module>,
    updateInfoMap: Map<String, ModuleUpdateInfo>,
    actions: ModuleActions,
    onModuleAddShortcut: (Module, ShortcutType) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
) {
    val loadingDialog = rememberLoadingDialog()
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        contentPadding = contentPadding,
        overscrollEffect = null,
    ) {
        items(
            items = modules,
            key = { it.id },
            contentType = { "module" }
        ) { module ->
            val currentModuleState = rememberUpdatedState(module)
            val moduleUpdateInfo = updateInfoMap[module.id] ?: ModuleUpdateInfo.Empty
            val content: @Composable () -> Unit = {
                ModuleItem(
                    module = module,
                    updateUrl = moduleUpdateInfo.downloadUrl,
                    onUninstall = {
                        actions.onRequestUninstallConfirmation(currentModuleState.value)
                    },
                    onUndoUninstall = {
                        scope.launch {
                            loadingDialog.withLoading { actions.onUndoUninstallModule(module) }
                        }
                    },
                    onCheckChanged = { _: Boolean ->
                        scope.launch {
                            loadingDialog.withLoading {
                                actions.onToggleModule(module)
                            }
                        }
                    },
                    onUpdate = {
                        scope.launch {
                            loadingDialog.withLoading {
                                actions.onRequestUpdateConfirmation(currentModuleState.value, moduleUpdateInfo)
                            }
                        }
                    },
                    onExecuteAction = {
                        actions.onExecuteModuleAction(currentModuleState.value)
                    },
                    onAddActionShortcut = { type: ShortcutType ->
                        onModuleAddShortcut(currentModuleState.value, type)
                    },
                    onOpenWebUi = {
                        if (module.hasWebUi) {
                            actions.onOpenWebUi(module)
                        }
                    }
                )
            }

            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModuleItem(
    module: Module,
    updateUrl: String,
    onUndoUninstall: () -> Unit,
    onUninstall: () -> Unit,
    onCheckChanged: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onExecuteAction: () -> Unit,
    onAddActionShortcut: (ShortcutType) -> Unit,
    onOpenWebUi: () -> Unit
) {
    val secondaryContainer = colorScheme.secondaryContainer.copy(alpha = 0.8f)
    val actionIconTint = colorScheme.onSurface.copy(alpha = if (isInDarkTheme()) 0.7f else 0.9f)
    val updateBg = colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    val updateTint = colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
    val hasUpdate = updateUrl.isNotEmpty()
    val textDecoration = if (module.remove) TextDecoration.LineThrough else null
    val hasDescription = module.description.isNotBlank()
    var expanded by rememberSaveable(module.id) { mutableStateOf(false) }

    // ── 「卡面」:这个模块自己的卡片背景图 ──
    // 没设置 → 一个 modifier 都不加,完全走原来的卡片样式(零回归);
    // 设置了 → 用**和卡片同一个 squircle 圆角**把图裁住并铺满整张卡。
    // 注意:Card 的底色是在 drawContent() 里画的(在咱们的图之后),所以必须把卡片
    // 底色改成透明 —— 否则图会被卡片自己的底色整个盖掉,等于白设。
    val context = LocalContext.current
    val cardArt by produceState<ImageBitmap?>(null, context, module.id, ModuleCardArt.version) {
        value = withContext(Dispatchers.IO) { ModuleCardArt.load(context, module.id, 1024) }
    }
    var showCardArtDialog by remember { mutableStateOf(false) }
    // 卡片真实像素尺寸:自裁取景框就按这个比例开,做到所见即所得
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val art = cardArt
    // 注意:colorScheme 是 @Composable 属性,不能在 drawWithContent 的 lambda 里读,
    // 所以先把压暗色在这里取好。
    val cardArtScrim = colorScheme.surfaceContainer.copy(alpha = ModuleCardArt.SCRIM)
    val cardArtModifier = if (art != null) {
        Modifier
            .squircleClip(CARD_ART_CORNER)
            .drawWithContent {
                drawCardArt(art, size, cardArtScrim)
                drawContent()
            }
    } else {
        Modifier
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .onSizeChanged { cardSize = it }
            .then(cardArtModifier),
        insideMargin = PaddingValues(16.dp),
        colors = if (art != null) {
            CardColors(Color.Transparent, colorScheme.onSurfaceContainer)
        } else {
            CardDefaults.defaultColors()
        },
        onClick = {
            if (hasDescription) expanded = !expanded
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 模块图标:默认显示首字色块,点一下可以换图/自裁
            com.sevenk.core.ui.component.miuix.ModuleIcon(
                moduleId = module.id,
                moduleName = module.name,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                val moduleVersion = stringResource(id = R.string.module_version)
                val moduleAuthor = stringResource(id = R.string.module_author)

                SubcomposeLayout { constraints ->
                    val spacingPx = 6.dp.roundToPx()
                    var nameTextLayout: TextLayoutResult? = null
                    val metaPlaceable = if (module.metamodule) {
                        subcompose("meta") {
                            Text(
                                text = "META",
                                fontSize = 12.sp,
                                color = updateTint,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(updateBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                fontWeight = FontWeight(750),
                                maxLines = 1,
                                softWrap = false
                            )
                        }.first().measure(Constraints(0, constraints.maxWidth, 0, constraints.maxHeight))
                    } else null

                    val reserved = (metaPlaceable?.width ?: 0) + if (metaPlaceable != null) spacingPx else 0
                    val nameMax = (constraints.maxWidth - reserved).coerceAtLeast(0)
                    val namePlaceable = subcompose("name") {
                        Text(
                            text = module.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight(550),
                            color = colorScheme.onSurface,
                            textDecoration = textDecoration,
                            onTextLayout = { nameTextLayout = it }
                        )
                    }.first().measure(Constraints(constraints.minWidth, nameMax, constraints.minHeight, constraints.maxHeight))

                    val width = (namePlaceable.width + reserved).coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = maxOf(namePlaceable.height, metaPlaceable?.height ?: 0)

                    layout(width, height) {
                        namePlaceable.placeRelative(0, 0)
                        val endX = nameTextLayout?.let { layoutRes ->
                            val last = (layoutRes.lineCount - 1).coerceAtLeast(0)
                            layoutRes.getLineRight(last).toInt()
                        } ?: namePlaceable.width
                        metaPlaceable?.placeRelative(endX + spacingPx, (height - (metaPlaceable.height)) / 2)
                    }
                }
                Text(
                    text = "$moduleVersion: ${module.version}",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurfaceVariantSummary,
                    textDecoration = textDecoration
                )
                Text(
                    text = "$moduleAuthor: ${module.author}",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 1.dp),
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurfaceVariantSummary,
                    textDecoration = textDecoration
                )
            }
            Switch(
                enabled = !module.update,
                checked = module.enabled,
                onCheckedChange = {
                    if (it != module.enabled) onCheckChanged(it)
                }
            )
        }

        if (hasDescription) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .animateContentSize(
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing
                        )
                    )
            ) {
                Text(
                    text = module.description,
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    textDecoration = textDecoration
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = colorScheme.outline.copy(alpha = 0.5f)
        )

        Row {
            AnimatedVisibility(
                visible = module.enabled && !module.remove && !module.update,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (module.hasActionScript) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 35.dp)
                                .widthIn(min = 35.dp)
                                .clip(CircleShape)
                                .background(secondaryContainer)
                                .combinedClickable(
                                    onClick = onExecuteAction,
                                    onLongClick = { onAddActionShortcut(ShortcutType.Action) }
                                )
                                .padding(
                                    start = if (!module.hasWebUi && !hasUpdate) 6.dp else 0.dp,
                                    end = if (!module.hasWebUi && !hasUpdate) 8.dp else 0.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                modifier = Modifier.size(24.dp),
                                imageVector = Icons.Rounded.PlayArrow,
                                tint = actionIconTint,
                                contentDescription = stringResource(R.string.action)
                            )
                            if (!module.hasWebUi && !hasUpdate) {
                                Text(
                                    modifier = Modifier.padding(start = 3.dp, end = 4.dp),
                                    text = stringResource(R.string.action),
                                    color = actionIconTint,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                    if (module.hasWebUi) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 35.dp)
                                .widthIn(min = 35.dp)
                                .clip(CircleShape)
                                .background(secondaryContainer)
                                .combinedClickable(
                                    onClick = onOpenWebUi,
                                    onLongClick = { onAddActionShortcut(ShortcutType.WebUI) }
                                )
                                .padding(horizontal = if (!module.hasActionScript && !hasUpdate) 10.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                modifier = Modifier.size(22.dp),
                                imageVector = Icons.Rounded.Code,
                                tint = actionIconTint,
                                contentDescription = stringResource(R.string.open)
                            )
                            if (!module.hasActionScript && !hasUpdate) {
                                Text(
                                    modifier = Modifier.padding(start = 4.dp, end = 2.dp),
                                    text = stringResource(R.string.open),
                                    color = actionIconTint,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = hasUpdate,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(
                    modifier = Modifier.padding(end = 8.dp),
                    backgroundColor = updateBg,
                    enabled = !module.remove,
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    onClick = onUpdate,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = MiuixIcons.UploadCloud,
                            tint = updateTint,
                            contentDescription = stringResource(R.string.module_update),
                        )
                        Text(
                            modifier = Modifier.padding(start = 4.dp, end = 3.dp),
                            text = stringResource(R.string.module_update),
                            color = updateTint,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            }
            // 「卡面」:给这个模块的卡片换背景图。放在卸载左边;
            // 做成纯图标按钮是为了不和「更新/卸载」的文字挤在一行把卡片撑爆。
            IconButton(
                modifier = Modifier.padding(end = 8.dp),
                minHeight = 35.dp,
                minWidth = 35.dp,
                onClick = { showCardArtDialog = true },
                backgroundColor = secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Rounded.Wallpaper,
                    tint = actionIconTint,
                    contentDescription = "卡面",
                )
            }
            IconButton(
                minHeight = 35.dp,
                minWidth = 35.dp,
                onClick = if (module.remove) onUndoUninstall else onUninstall,
                backgroundColor = if (module.remove) {
                    secondaryContainer.copy(alpha = 0.8f)
                } else {
                    secondaryContainer
                },
            ) {
                val animatedPadding by animateDpAsState(
                    targetValue = if (!hasUpdate) 10.dp else 0.dp,
                    animationSpec = tween(durationMillis = 300)
                )
                Row(
                    modifier = Modifier.padding(horizontal = animatedPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = if (module.remove) {
                            MiuixIcons.Undo
                        } else {
                            MiuixIcons.Delete
                        },
                        tint = actionIconTint,
                        contentDescription = null
                    )
                    AnimatedVisibility(
                        visible = !hasUpdate,
                        enter = expandHorizontally(),
                        exit = shrinkHorizontally()
                    ) {
                        Text(
                            modifier = Modifier.padding(start = 4.dp, end = 3.dp),
                            text = stringResource(
                                if (module.remove) R.string.undo else R.string.uninstall
                            ),
                            color = actionIconTint,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (showCardArtDialog) {
        // 取景框比例 = 卡片真实宽/高。卡片高度会随「描述展开/收起」变化,
        // 所以取"点开这一刻"的值;万一是 0(还没量到)就退回一个常见宽卡片比例。
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

/** 卡片圆角:与 Miuix Card 的默认 cornerRadius 一致(CardDefaults.CornerRadius = 16.dp) */
private val CARD_ART_CORNER = 16.dp

/** 「卡面」设置框:选图(并自裁) / 重新自裁 / 移除背景图 / 取消 */
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (ModuleCardArt.saveOriginal(context, moduleId, uri)) {
                // 选完直接进自裁框
                cropping = true
            } else {
                Toast.makeText(context, "这张图读不出来,换一张试试", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 【只用这一个 WindowDialog】菜单 / 自裁 只切内容,不再叠两个窗口
    // (v0.13.56 就是因为菜单和自裁分别是两个窗口、旧窗还在退场动画时把触摸全吃掉,
    //  导致自裁框"看得见却点不动")。
    WindowDialog(
        show = true,
        backgroundColor = miuixDialogColor(),
        title = if (cropping) "自裁卡面" else "卡面",
        // 【必须传】WindowDialog 的 onDismissRequest 默认是 null,不传的话返回键和
        // 点击外部都不响应 —— 万一下面按钮不可见就彻底退不出来(详见 ModuleIcon.kt)。
        onDismissRequest = onDismiss,
        content = {
            if (cropping) {
                ModuleCardArtCropContent(
                    moduleId = moduleId,
                    aspect = aspect,
                    onCancel = onDismiss,
                    onApplied = onDismiss,
                )
            } else {
                Column {
                    Text(
                        text = "给「$moduleName」这张卡片换一张背景图;选完可以拖动/双指缩放自裁。",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(
                        text = "换一张图片(并自裁)",
                        onClick = { picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    if (hasOriginal) {
                        TextButton(
                            text = "自裁这张图",
                            onClick = { cropping = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (hasArt) {
                        TextButton(
                            text = "移除背景图",
                            onClick = {
                                ModuleCardArt.clear(context, moduleId)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/**
 * 自裁卡面:取景框比例 = **卡片真实比例**(aspect),所以所见即所得。
 *
 * 尺寸按**可用高度**算 —— WindowDialog 的内容区没有滚动,超高的部分会被直接裁掉,
 * 下面那排按钮就会被顶出屏幕、弹窗退不出来(v0.13.57 就是这么锁死的)。
 */
@Composable
private fun ModuleCardArtCropContent(
    moduleId: String,
    aspect: Float,
    onCancel: () -> Unit,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, context, moduleId, ModuleCardArt.version) {
        value = withContext(Dispatchers.IO) { ModuleCardArt.loadOriginal(context, moduleId) }
    }
    val saved = remember(moduleId) { ModuleCardArt.loadCrop(context, moduleId) }
    var biasX by remember { mutableFloatStateOf(saved?.biasX ?: 0f) }
    var biasY by remember { mutableFloatStateOf(saved?.biasY ?: 0f) }
    var zoom by remember { mutableFloatStateOf(saved?.zoom ?: 1f) }
    val a = aspect.coerceIn(0.5f, 4f)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val budget = if (maxHeight.value.isFinite()) maxHeight - 150.dp else 220.dp
        val previewW = minOf(maxWidth * 0.92f, budget * a, 380.dp).coerceAtLeast(120.dp)
        val previewH = (previewW / a).coerceAtLeast(64.dp)

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
                            biasX = (biasX - pan.x / w * 2f).coerceIn(-1f, 1f)
                            biasY = (biasY - pan.y / h * 2f).coerceIn(-1f, 1f)
                            zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
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
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "缩小",
                    onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                    modifier = Modifier.weight(1f),
                    enabled = zoom > 1f,
                )
                TextButton(
                    text = "放大",
                    onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                    modifier = Modifier.weight(1f),
                    enabled = zoom < 4f,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "复位",
                    onClick = {
                        biasX = 0f
                        biasY = 0f
                        zoom = 1f
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确定",
                    onClick = {
                        if (ModuleCardArt.bake(context, moduleId, a, biasX, biasY, zoom)) {
                            onApplied()
                        } else {
                            Toast.makeText(context, "保存失败,重新选一张图试试", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
