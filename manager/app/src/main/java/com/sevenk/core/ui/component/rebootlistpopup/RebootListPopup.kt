package com.sevenk.core.ui.component.rebootlistpopup

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sevenk.core.Natives
import com.sevenk.core.R
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode
import com.sevenk.core.ui.component.dialog.rememberConfirmDialog
import com.sevenk.core.ui.util.reboot

data class RebootListOption(
    @param:StringRes val labelRes: Int,
    val reason: String,
)

@Composable
fun getRebootListOption(): List<RebootListOption> {
    val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?

    @Suppress("DEPRECATION")
    val isRebootingUserspaceSupported = pm?.isRebootingUserspaceSupported == true

    return buildList {
        add(RebootListOption(R.string.reboot, ""))
        if (isRebootingUserspaceSupported) {
            add(RebootListOption(R.string.reboot_userspace, "userspace"))
        }
        add(RebootListOption(R.string.reboot_soft, "soft_reboot"))
        add(RebootListOption(R.string.reboot_recovery, "recovery"))
        add(RebootListOption(R.string.reboot_bootloader, "bootloader"))
        add(RebootListOption(R.string.reboot_download, "download"))
        add(RebootListOption(R.string.reboot_edl, "edl"))
    }
}

/** Reboots on selection, but confirms first in jailbreak mode where a plain reboot drops root. */
@Composable
fun rememberRebootAction(): (String) -> Unit {
    val title = stringResource(R.string.reboot)
    val message = stringResource(R.string.jailbreak_reboot_warning)
    val confirmDialog = rememberConfirmDialog(onConfirm = { reboot() })

    return remember(title, message, confirmDialog) {
        { reason ->
            // 🟠（v2.19）这里在**点重启**时才读 JNI：裸调一旦抛异常，用户看到的就是"点重启没反应/闪退"。
            // 读不到按 false（普通重启）走，比卡住安全。
            if (runCatching { Natives.isLateLoadMode }.getOrDefault(false) && reason.isEmpty()) {
                confirmDialog.showConfirm(title = title, content = message)
            } else {
                reboot(reason)
            }
        }
    }
}

@Composable
fun RebootListPopup() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> RebootListPopupMiuix()
        UiMode.Material -> RebootListPopupMaterial()
    }
}
