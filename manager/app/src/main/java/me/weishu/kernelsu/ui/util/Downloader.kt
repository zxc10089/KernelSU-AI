package me.weishu.kernelsu.ui.util

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
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

    DownloadManager.downloads
        .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
        .first { map ->
            val status = map[downloadId]?.status
            status == DownloadManager.Status.COMPLETED ||
                status == DownloadManager.Status.FAILED
        }
}

/**
 * Release feed this manager checks for ITS OWN updates, as "owner/repo".
 *
 * Do NOT point this at the official KernelSU repository. This fork installs under a different
 * applicationId (KSU_PACKAGE_NAME in manager/gradle.properties) but is signed with the SAME
 * certificate, and the kernel crowns whichever package presents that certificate -- so the
 * official APK advertised here is also a manager the kernel would hand control to. Offering it
 * quietly invites users to replace this manager with the one this fork exists to avoid.
 *
 * Blank means "this fork publishes no releases", which makes the check a no-op. Set it if you
 * publish your own builds.
 */
private const val UPDATE_REPO = ""

fun checkNewVersion(): LatestVersionInfo {
    // Never fall through to a hardcoded upstream repository: a fork with no release channel of its
    // own has nothing to offer, and anything else would be advertising a rival manager.
    if (UPDATE_REPO.isBlank()) return LatestVersionInfo()
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
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
