package com.sevenk.core.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.util.listAppProfileTemplates
import com.sevenk.core.ui.util.setSepolicy
import com.sevenk.core.ui.viewmodel.getTemplateInfoById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * @author weishu
 * @date 2023/10/21.
 */
@Composable
fun TemplateConfigMiuix(
    modifier: Modifier = Modifier,
    profile: Natives.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit
) {
    // 🔴（v2.18）**不能在组合期（主线程）跑 root shell**：`listAppProfileTemplates()`
    // 会 `getRootShell().newJob().add("ksud profile list-templates").exec()` —— 一次 su 往返
    // （shell 没缓存时还要 fork/exec + SHELL_TEST/id 同步，最坏数秒）。旧写法是裸调用、
    // 每次重组都跑一次 ⇒ 进 profile 页卡顿/ANR。改成"先给空列表，异步取回后再刷新"。
    var profileTemplates by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        profileTemplates = withContext(Dispatchers.IO) { listAppProfileTemplates() }
    }
    val noTemplates = profileTemplates.isEmpty()

    if (noTemplates) {
        ArrowPreference(
            modifier = modifier,
            title = stringResource(R.string.app_profile_template_create),
            startAction = {
                Icon(
                    Icons.Rounded.Create,
                    null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MiuixTheme.colorScheme.onBackground
                )
            },
            onClick = onManageTemplate,
        )
    } else {
        val template = profile.rootTemplate ?: profileTemplates[0]

        Column(modifier = modifier) {
            OverlayDropdownPreference(
                title = stringResource(R.string.profile_template),
                items = profileTemplates,
                selectedIndex = profileTemplates.indexOf(template).takeIf { it >= 0 } ?: 0,
                onSelectedIndexChange = { index ->
                    if (index < 0 || index >= profileTemplates.size) return@OverlayDropdownPreference
                    val selected = profileTemplates[index]
                    val templateInfo = getTemplateInfoById(selected)
                    if (templateInfo != null && setSepolicy(selected, templateInfo.rules.joinToString("\n"))) {
                        onProfileChange(
                            profile.copy(
                                rootTemplate = selected,
                                rootUseDefault = false,
                                uid = templateInfo.uid,
                                gid = templateInfo.gid,
                                groups = templateInfo.groups,
                                capabilities = templateInfo.capabilities,
                                context = templateInfo.context,
                                namespace = templateInfo.namespace,
                            )
                        )
                    }
                },
                maxHeight = 280.dp
            )
            ArrowPreference(
                title = stringResource(R.string.app_profile_template_view),
                onClick = { onViewTemplate(template) }
            )
        }
    }
}