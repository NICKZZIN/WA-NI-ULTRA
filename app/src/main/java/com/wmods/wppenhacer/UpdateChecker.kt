package com.wmods.wppenhacer

import android.app.Activity
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.YukiLog
import io.noties.markwon.Markwon
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateChecker(private val mActivity: Activity) : Runnable {

    data class UpdateResult(
        val versionLabel: String,
        val hash: String,
        val changelog: String,
        val publishedAt: String,
        val downloadUrl: String,
        val isNewVersion: Boolean
    )

    companion object {
        private const val REPO_SLUG = "NICKZZIN/WA-NI-ULTRA"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/$REPO_SLUG/releases/latest"
        private const val LATEST_COMMIT_API =
            "https://api.github.com/repos/$REPO_SLUG/commits/master"
        const val GITHUB_RELEASES_URL = "https://github.com/$REPO_SLUG/releases"
        const val GITHUB_ACTIONS_URL = "https://github.com/$REPO_SLUG/actions"
        const val TELEGRAM_UPDATE_URL = "https://t.me/NickC137"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private fun buildGithubRequest(url: String): Request = Request.Builder()
            .url(url)
            .header("User-Agent", "WA-NI-ULTRA")
            .header("Accept", "application/vnd.github+json")
            .build()

        @JvmStatic
        fun checkLatest(currentVersionName: String): UpdateResult? {
            // 1. Try latest published GitHub Release first
            httpClient.newCall(buildGithubRequest(LATEST_RELEASE_API)).execute().use { response ->
                if (response.isSuccessful) {
                    val release = JSONObject(response.body.string())
                    val tagName = release.optString("tag_name", "").trim()
                    if (tagName.isNotEmpty()) {
                        val hashPart = tagName.substringAfterLast("-", "").trim()
                        val compareToken = hashPart.ifEmpty { tagName }
                        val isNew = !currentVersionName.contains(compareToken, ignoreCase = true)
                        return UpdateResult(
                            versionLabel = tagName,
                            hash = compareToken,
                            changelog = release.optString("body", "").trim()
                                .ifEmpty { "Release $tagName" },
                            publishedAt = release.optString("published_at", ""),
                            downloadUrl = release.optString("html_url", GITHUB_RELEASES_URL)
                                .ifBlank { GITHUB_RELEASES_URL },
                            isNewVersion = isNew
                        )
                    }
                } else if (response.code != 404) {
                    return null
                }
            }

            // 2. Fallback when repository has no published Releases yet: compare master HEAD commit
            httpClient.newCall(buildGithubRequest(LATEST_COMMIT_API)).execute().use { response ->
                if (!response.isSuccessful) return null
                val commitRoot = JSONObject(response.body.string())
                val fullSha = commitRoot.optString("sha", "").trim()
                if (fullSha.length < 7) return null
                val sha8 = fullSha.substring(0, minOf(8, fullSha.length)).uppercase(Locale.ROOT)
                val commitObj = commitRoot.optJSONObject("commit")
                val message = commitObj?.optString("message", "")?.trim().orEmpty()
                val date = commitObj?.optJSONObject("committer")?.optString("date", "").orEmpty()
                val isNew = !currentVersionName.contains(sha8, ignoreCase = true) &&
                    currentVersionName != "UNKNOWN"
                return UpdateResult(
                    versionLabel = sha8,
                    hash = sha8,
                    changelog = message.ifEmpty { "Commit $sha8" },
                    publishedAt = date,
                    downloadUrl = GITHUB_ACTIONS_URL,
                    isNewVersion = isNew
                )
            }
        }
    }

    override fun run() {
        try {
            val packageInfo = try {
                mActivity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
            } catch (e: Exception) {
                YukiLog.log(e)
                return
            }
            val currentVersion = packageInfo.versionName.orEmpty()
            val result = checkLatest(currentVersion) ?: return
            val isIgnored = WppCore.getPrivString("ignored_version", "") == result.hash

            if (result.isNewVersion && !isIgnored) {
                mActivity.runOnUiThread {
                    showUpdateDialog(result)
                }
            }
        } catch (e: Exception) {
            YukiLog.log(e)
        }
    }

    private fun showUpdateDialog(info: UpdateResult) {
        try {
            val markwon = Markwon.create(mActivity)
            val dialog = AlertDialogWpp(mActivity)

            val formattedDate = formatPublishedDate(info.publishedAt)

            val message = buildString {
                append("📦 **Versão:** `").append(info.versionLabel).append("`\n")
                if (formattedDate.isNotEmpty()) {
                    append("📅 **Data:** ").append(formattedDate).append("\n")
                }
                append("\n### Novidades\n\n").append(info.changelog)
            }

            dialog.setTitle(Utils.getString(R.string.update_dialog_title))
            dialog.setMessage(markwon.toMarkdown(message))
            dialog.setNegativeButton(Utils.getString(R.string.update_ignore)) { d, _ ->
                WppCore.setPrivString("ignored_version", info.hash)
                d.dismiss()
            }
            dialog.setPositiveButton(Utils.getString(R.string.update_now)) { d, _ ->
                Utils.openLink(mActivity, info.downloadUrl)
                d.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            YukiLog.log(e)
        }
    }

    private fun formatPublishedDate(isoDate: String?): String {
        if (isoDate.isNullOrEmpty()) return ""

        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = isoFormat.parse(isoDate)
            if (date != null) {
                val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                displayFormat.format(date)
            } else ""
        } catch (e: Exception) {
            YukiLog.log(e)
            ""
        }
    }
}
