package com.sevenk.core.ui.util

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import com.sevenk.core.ksuApp
import com.sevenk.core.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * @author weishu
 * @date 2023/6/22.
 */
suspend fun download(
    url: String,
    fileName: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {}
) {
    onDownloading()

    val downloadId = DownloadManager.enqueue(
        context = ksuApp,
        url = url,
        fileName = fileName,
        onCompleted = onDownloaded,
    )

    // 🔴（v2.19）等"下载结束"必须带超时：旧写法 `.first { COMPLETED || FAILED }` 在
    // DownloadService 被杀、或系统压根没回写状态时会**永远挂住** —— 这个协程不返回，
    // 调用方的 `isDownloading` 就一直是 true，界面永远转圈、按钮点不动。
    // 给 10 分钟封顶，超时按失败处理（见下面 finished == null 分支）。
    val finished = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
        DownloadManager.downloads
            .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
            .first { map ->
                val status = map[downloadId]?.status
                status == DownloadManager.Status.COMPLETED ||
                    status == DownloadManager.Status.FAILED
            }
    }
    if (finished == null) {
        // 🔴（v2.19）超时**按失败处理**，并且走管理器已有的那个失败出口
        // `DownloadManager.markFailed` —— DownloadService 的"下载出错"和"用户取消"
        // 两条路调的都是它（见 DownloadService.kt:164 / :88），所以超时后的状态与真失败
        // **完全一致**，没有新造第三套。
        // 为什么必须补这一句：不落 FAILED 的话这条记录会永久停在 DOWNLOADING/PENDING，
        // 而 `DownloadManager.enqueue` 的同 URL 去重判据正是 `PENDING || DOWNLOADING`
        // ⇒ 用户点重试会拿回同一个死 id，同一个文件永远下载不了。
        // 与真失败一样，这里**不回调 onDownloaded**（完成回调已被 markFailed 摘掉）。
        DownloadManager.markFailed(downloadId, "timeout")
        android.util.Log.w(TAG, "download timeout(${DOWNLOAD_TIMEOUT_MS}ms), 按失败处理: $url")
    }
}

private const val TAG = "Downloader"

/** 等一次下载结束的最长时间（10 分钟）：超时按失败处理，见 [download]。 */
private const val DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1000

/*
 * 这是第三方分支(7kimisu),内核签名是自定义的:官方 KernelSU 的管理器装上来
 * 【拿不到 root】,版本号也不通用。所以不做官方更新检查 —— 否则会一直提示
 * "有新版本",装了反而废掉。以后有了自己的发布地址,填上 UPDATE_CHECK_URL 即可。
 */
private const val UPDATE_CHECK_URL = ""

fun checkNewVersion(): LatestVersionInfo {
    if (UPDATE_CHECK_URL.isEmpty()) return LatestVersionInfo()
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = UPDATE_CHECK_URL
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body.string()
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".apk")) {
                        continue
                    }

                    val regex = Regex("v(.+?)_(\\d+)-")
                    val matchResult = regex.find(name) ?: continue
                    matchResult.groupValues[1]
                    val versionCode = matchResult.groupValues[2].toInt()
                    val downloadUrl = asset.getString("browser_download_url")

                    return LatestVersionInfo(
                        versionCode,
                        downloadUrl,
                        changelog
                    )
                }

            }
    }
    return defaultValue
}
