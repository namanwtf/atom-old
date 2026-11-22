package io.github.mangi.eta.data.update

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Latest GitHub Release info; [version] has the `v` prefix removed from the tag. */
internal data class AppLatestRelease(
    val version: String,
    val url: String,
)

/**
 * App update check: reads the latest GitHub Release and compares it with the current version.
 *
 * Version numbers follow the release process convention of a `v` prefix plus dot-separated digits (v3.0.6); comparison is done per segment: when both segments are numeric
 * compare numerically, otherwise compare as strings. The current version's build-type suffix (-debug) and CI build metadata (+commit hash) do not participate in the comparison.
 * All network and response errors are thrown uniformly as [IOException] and surfaced by the caller.
 */
internal object AppUpdateChecker {

    fun fetchLatest(): AppLatestRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    AppStrings.get(
                        R.string.update_err_github_release_http,
                        "GitHub Release request failed（HTTP %1\$d）",
                        response.code,
                    ),
                )
            }
            val json = runCatching { JSONObject(response.body.string()) }
                .getOrElse {
                    throw IOException(
                        AppStrings.get(
                            R.string.update_err_github_release_invalid_json,
                            "GitHub Release response is not valid JSON",
                        ),
                        it,
                    )
                }
            val tag = json.optString("tag_name").trim()
            val url = json.optString("html_url").trim()
            if (tag.isEmpty() || url.isEmpty()) {
                throw IOException(
                    AppStrings.get(
                        R.string.update_err_github_release_missing_fields,
                        "GitHub Release response is missing tag_name/html_url",
                    ),
                )
            }
            return AppLatestRelease(version = tag.removePrefix("v"), url = url)
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.removePrefix("v").split('.')
        val currentParts =
            current.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(index) ?: "0"
            val currentPart = currentParts.getOrNull(index) ?: "0"
            val latestNumber = latestPart.toIntOrNull()
            val currentNumber = currentPart.toIntOrNull()
            if (latestNumber != null && currentNumber != null) {
                if (latestNumber != currentNumber) return latestNumber > currentNumber
            } else {
                val compared = latestPart.compareTo(currentPart)
                if (compared != 0) return compared > 0
            }
        }
        return false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val LATEST_RELEASE_URL =
        "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest"
    private const val USER_AGENT = "Atom-Update-Checker"
}
