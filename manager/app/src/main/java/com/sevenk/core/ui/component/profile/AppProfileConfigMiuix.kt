package com.sevenk.core.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.component.miuix.EditText
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun AppProfileConfigMiuix(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            EditText(
                title = stringResource(R.string.profile_name),
                value = profile.name,
                onValueChange = { onProfileChange(profile.copy(name = it)) },
                enabled = enabled,
            )
        }

        SwitchPreference(
            title = stringResource(R.string.profile_umount_modules),
            summary = stringResource(R.string.profile_umount_modules_summary),
            checked = if (enabled) {
                profile.umountModules
            } else {
                // 🟠（v2.19）组合期裸调 JNI：内核接口失效时会抛异常把整个 profile 页带崩。
                // 读不到按 false 显示（和"没有默认 umount"一致），比闪退安全。
                runCatching { Natives.isDefaultUmountModules() }.getOrDefault(false)
            },
            enabled = enabled,
            onCheckedChange = {
                onProfileChange(
                    profile.copy(
                        umountModules = it,
                        nonRootUseDefault = false
                    )
                )
            }
        )
    }
}

@Preview
@Composable
private fun AppProfileConfigPreview() {
    var profile by remember { mutableStateOf(Natives.Profile("")) }
    AppProfileConfigMiuix(fixedName = true, enabled = false, profile = profile) {
        profile = it
    }
}
