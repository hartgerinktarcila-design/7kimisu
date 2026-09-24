package com.sevenk.core.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.component.material.SegmentedColumn
import com.sevenk.core.ui.component.material.SegmentedSwitchItem
import com.sevenk.core.ui.component.material.SegmentedTextField

@Composable
fun AppProfileConfigMaterial(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                content = listOf {
                    SegmentedTextField(
                        value = profile.name,
                        onValueChange = { onProfileChange(profile.copy(name = it)) },
                        label = stringResource(R.string.profile_name),
                        readOnly = !enabled,
                        singleLine = true
                    )
                }
            )
        }

        SegmentedColumn(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            content = listOf {
                SegmentedSwitchItem(
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
        )
    }
}
