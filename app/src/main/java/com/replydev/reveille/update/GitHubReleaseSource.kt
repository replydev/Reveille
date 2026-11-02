package com.replydev.reveille.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * `GET /repos/replydev/Reveille/releases/latest`, unauthenticated (60 requests per hour per IP), with ETag
 * revalidation. `/latest` already excludes drafts and pre-releases.
 */
class GitHubReleaseSource(
    private val http: Http,
    private val userAgent: String,
    private val clock: Clock = Clock.systemUTC(),
    private val url: String = LATEST_URL,
) : ReleaseSource {
    override suspend fun latest(etag: String?): FetchResult = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("User-Agent", userAgent)
            if (etag != null) put("If-None-Match", etag)
        }
        val response = try {
            http.get(url, headers)
        } catch (_: IOException) {
            return@withContext FetchError.Offline
        }
        when (response.code) {
            200 -> parse(response.body, response.headers["etag"])
            304 -> FetchResult.NotModified
            404 -> FetchResult.NoRelease(null)
            403, 429 -> rateLimit(response) ?: FetchError.Http(response.code)
            else -> FetchError.Http(response.code)
        }
    }

    private fun rateLimit(response: HttpResponse): FetchError.RateLimited? {
        val retryAfter = response.headers["retry-after"]?.toLongOrNull()
        val exhausted = response.headers["x-ratelimit-remaining"] == "0"
        if (response.code == 403 && retryAfter == null && !exhausted) return null
        val until = when {
            retryAfter != null -> clock.instant().plusSeconds(retryAfter)
            else -> response.headers["x-ratelimit-reset"]?.toLongOrNull()?.let(Instant::ofEpochSecond)
        }
        return FetchError.RateLimited(until)
    }

    private fun parse(body: String, etag: String?): FetchResult = try {
        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val version = ReleaseVersion.parseTag(tag)
        if (version == null || json.optBoolean("draft") || json.optBoolean("prerelease")) {
            FetchResult.NoRelease(etag)
        } else {
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).map(assets::getJSONObject).associateBy { it.getString("name") }
            val apk = byName["reveille-$tag.apk"]
            val sha256 = byName["reveille-$tag.apk.sha256"]
            if (apk == null || sha256 == null) {
                FetchError.Malformed
            } else {
                val release = Release(
                    tag = tag,
                    versionCode = version.versionCode,
                    publishedAt = Instant.parse(json.getString("published_at")),
                    notes = json.optString("body").takeUnless { json.isNull("body") }.orEmpty(),
                    apkUrl = apk.getString("browser_download_url"),
                    apkSize = apk.getLong("size"),
                    sha256Url = sha256.getString("browser_download_url"),
                    htmlUrl = json.getString("html_url"),
                )
                FetchResult.Found(release, etag)
            }
        }
    } catch (_: JSONException) {
        FetchError.Malformed
    } catch (_: DateTimeParseException) {
        FetchError.Malformed
    }

    companion object {
        const val REPO_URL = "https://github.com/replydev/Reveille"
        const val LATEST_URL = "https://api.github.com/repos/replydev/Reveille/releases/latest"
    }
}
