package com.sevenk.core.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import com.sevenk.core.ui.LocalUiMode
import com.sevenk.core.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
