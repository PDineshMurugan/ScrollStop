package com.scrollcounter.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.scrollcounter.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val name: String,
    val body: String,
    val downloadUrl: String,
    val htmlUrl: String,
    val publishedAt: String
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

object UpdateManager {

    const val GITHUB_OWNER = "PDineshMurugan"
    const val GITHUB_REPO = "scrollstop"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val GITHUB_ISSUES_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/issues"
    const val GITHUB_BUG_REPORT_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/issues/new?template=bug_report.md&title=%5BBug%5D+"
    const val GITHUB_FEATURE_REQUEST_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/issues/new?template=feature_request.md&title=%5BFeature%5D+"
    const val GITHUB_LATEST_RELEASE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val DIRECT_APK_DOWNLOAD_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/scrollstop.apk"

    private const val API_RELEASES_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    suspend fun checkForUpdates(force: Boolean = false): UpdateStatus {
        if (!force && _status.value is UpdateStatus.UpdateAvailable) {
            return _status.value
        }

        _status.value = UpdateStatus.Checking

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(API_RELEASES_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "ScrollStop-Android-App")
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    val tagName = json.optString("tag_name", "")
                    val releaseName = json.optString("name", tagName)
                    val body = json.optString("body", "")
                    val htmlUrl = json.optString("html_url", GITHUB_LATEST_RELEASE_URL)
                    val publishedAt = json.optString("published_at", "")

                    // Find APK asset download url if attached
                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    if (apkUrl.isEmpty()) {
                        apkUrl = DIRECT_APK_DOWNLOAD_URL
                    }

                    val cleanRemote = cleanVersion(tagName)
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewerVersion(cleanRemote, currentVersion)) {
                        val releaseInfo = AppReleaseInfo(
                            tagName = tagName,
                            versionName = cleanRemote,
                            name = releaseName,
                            body = body,
                            downloadUrl = apkUrl,
                            htmlUrl = htmlUrl,
                            publishedAt = publishedAt
                        )
                        val result = UpdateStatus.UpdateAvailable(releaseInfo)
                        _status.value = result
                        result
                    } else {
                        val result = UpdateStatus.UpToDate(currentVersion)
                        _status.value = result
                        result
                    }
                } else if (responseCode == 404) {
                    // No release created yet on github
                    val result = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
                    _status.value = result
                    result
                } else {
                    val result = UpdateStatus.Error("GitHub response error (HTTP $responseCode)")
                    _status.value = result
                    result
                }
            } catch (e: Exception) {
                val result = UpdateStatus.Error(e.message ?: "Failed to check for updates")
                _status.value = result
                result
            }
        }
    }

    fun cleanVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    /**
     * Compares two semantic version strings (e.g., "1.0.1" vs "1.0.0").
     * Returns true if remote is strictly newer than current.
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val remoteParts = remote.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    fun openBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
