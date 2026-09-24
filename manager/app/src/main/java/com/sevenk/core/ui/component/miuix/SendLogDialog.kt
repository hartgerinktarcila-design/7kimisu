package com.sevenk.core.ui.component.miuix

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.BuildConfig
import com.sevenk.core.R
import com.sevenk.core.ui.component.dialog.LoadingDialogHandle
import com.sevenk.core.ui.util.getBugreportFile
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SendLogDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    loadingDialog: LoadingDialogHandle,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logSavedText = stringResource(R.string.log_saved)
    val sendLogText = stringResource(R.string.send_log)
    val exportBugreportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            loadingDialog.show()
            // getBugreportFile 要起 root shell、跑一长串命令，任何一步都可能抛；
            // 协程里没捕获 = 直接闪退。这里整段兜底：失败就不提示"已保存"，只记日志。
            val saved = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    getBugreportFile(context).inputStream().use {
                        it.copyTo(output)
                    }
                }
            }.onFailure { android.util.Log.w("SendLogDialog", "导出日志失败", it) }.isSuccess
            loadingDialog.hide()
            if (saved) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, logSavedText, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    OverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                text = stringResource(R.string.send_log),
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = colorScheme.onSurface
            )
            ArrowPreference(
                title = stringResource(id = R.string.save_log),
                startAction = {
                    Icon(
                        Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
                    val current = LocalDateTime.now().format(formatter)
                    exportBugreportLauncher.launch("KernelSU_bugreport_${current}.tar.gz")
                    onDismissRequest()
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            ArrowPreference(
                title = stringResource(id = R.string.send_log),
                startAction = {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    scope.launch {
                        onDismissRequest()
                        // 与"保存日志"同一条链路（同样会抛），整段兜底；
                        // withLoading 只负责关掉加载框，不会吞异常，所以异常得在这里接住。
                        runCatching {
                            val bugreport = loadingDialog.withLoading {
                                withContext(Dispatchers.IO) {
                                    getBugreportFile(context)
                                }
                            }

                            val uri: Uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                                    bugreport
                                )

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, uri)
                                setDataAndType(uri, "application/gzip")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }

                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    sendLogText
                                )
                            )
                        }.onFailure { android.util.Log.w("SendLogDialog", "分享日志失败", it) }
                    }
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = {
                    onDismissRequest()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp)
                    .padding(horizontal = 24.dp)
            )
        }
    )
}
