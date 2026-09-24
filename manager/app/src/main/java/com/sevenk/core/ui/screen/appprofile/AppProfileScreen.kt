package com.sevenk.core.ui.screen.appprofile

import android.util.Log
import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.navigation3.LocalNavigator
import com.sevenk.core.ui.navigation3.Route
import com.sevenk.core.ui.util.forceStopApp
import com.sevenk.core.ui.util.getSepolicy
import com.sevenk.core.ui.util.launchApp
import com.sevenk.core.ui.util.restartApp
import com.sevenk.core.ui.util.setSepolicy
import com.sevenk.core.ui.viewmodel.SuperUserViewModel
import com.sevenk.core.ui.viewmodel.getTemplateInfoById

@Composable
fun AppProfileScreen(uid: Int) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        // 🟢 v2.15：`getAppProfile` 现在声明成 `Profile?`（native 结构性 JNI 失败才为 null）。
        //    以前声明非空，调用方直接 `.copy(...)` —— native 一旦返回 null 就是 NPE 闪退。
        //    读不到就用一个空 Profile（等价于"没有任何 profile"），界面照常出。
        (Natives.getAppProfile(packageName, uid) ?: Natives.Profile()).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }
    // 🔴（v2.18）`getSepolicy()` 是 root shell 往返（`ksud profile get-sepolicy <pkg>`）。
    // 旧写法把它塞进上面的 `remember{}` ⇒ 每次**组合期（主线程）**同步跑一次 su
    //（shell 没缓存时还要 fork/exec + SHELL_TEST/id，最坏数秒）⇒ 点进 profile 页白屏/ANR。
    // 现在：界面先用不带 rules 的 profile 画出来，rules 异步取回后再补上。
    LaunchedEffect(uid, packageName) {
        if (!initialProfile.allowSu || primaryAppInfo.special) return@LaunchedEffect
        val rules = withContext(Dispatchers.IO) { getSepolicy(packageName) }
        // 只在用户还没改过（仍是初始那份的 rules）时补，别覆盖用户的编辑
        if (profile.rules == initialProfile.rules) {
            profile = profile.copy(rules = rules)
        }
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        // 🔴（v2.19）这三个都是 **root shell 往返**（getRootShell + am/cmd package ...）。
        // 旧写法用函数引用直接挂在 onClick 上 ⇒ 在**主线程**同步跑 su，shell 没缓存时
        // 还要 fork/exec，最坏数秒 ⇒ 点一下白屏/ANR。现在挪到 IO 协程，主线程只负责发起。
        onLaunchApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { launchApp(pkg, userId) } } },
        onForceStopApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { forceStopApp(pkg, userId) } } },
        onRestartApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { restartApp(pkg, userId) } } },
        onViewTemplate = { templateId ->
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onProfileChange = { updatedProfile ->
            scope.launch {
                val profileToSave = if (primaryAppInfo.special) {
                    updatedProfile.copy(allowSu = false)
                } else {
                    updatedProfile
                }
                if (profileToSave.allowSu) {
                    if (uid < 2000 && uid != 1000) {
                        showMessage(suNotAllowed)
                        return@launch
                    }
                    if (!profileToSave.rootUseDefault
                        && profileToSave.rules.isNotEmpty()
                        && !primaryAppInfo.special
                    ) {
                        // 🔴（v2.19）`setSepolicy` 是 root shell 往返（ksud profile set-sepolicy）。
                        // 旧写法在**主线程**协程里同步跑 ⇒ 保存规则时卡住/ANR。挪到 IO；
                        // 返回 false 时的提示行为（failToUpdateSepolicy）原样保留。
                        val sepolicyOk = withContext(Dispatchers.IO) {
                            setSepolicy(profileToSave.name, profileToSave.rules)
                        }
                        if (!sepolicyOk) {
                            showMessage(failToUpdateSepolicy)
                            return@launch
                        }
                    }
                }
                // 🟠（v2.19）`setAppProfile` 也是裸 JNI：native 层异常一旦抛出来，
                // 这个 `scope.launch` 直接把 App 带崩（profile 页保存时闪退）。
                // 现在兜住异常、记一行日志后走**原来的失败分支**，成功路径的语义一点没变。
                val saved = runCatching { Natives.setAppProfile(profileToSave) }
                    .onFailure { Log.w("AppProfileScreen", "setAppProfile 调用失败", it) }
                    .getOrDefault(false)
                if (!saved) {
                    showMessage(failToUpdateAppProfile)
                } else {
                    profile = profileToSave
                    if (uiMode == UiMode.Material) {
                        viewModel.loadAppList()
                    }
                }
            }
        },
    )

    when (uiMode) {
        UiMode.Miuix -> AppProfileScreenMiuix(
            state = state,
            actions = actions,
        )

        UiMode.Material -> AppProfileScreenMaterial(
            state = state,
            actions = actions,
            snackBarHost = snackbarHost,
        )
    }
}
