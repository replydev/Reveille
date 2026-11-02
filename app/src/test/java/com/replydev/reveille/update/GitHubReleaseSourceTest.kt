package com.replydev.reveille.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.update.FetchResult.Found
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Robolectric for `org.json`, which is a stub in plain JVM tests. */
@RunWith(AndroidJUnit4::class)
class GitHubReleaseSourceTest {
    private val now = Instant.parse("2026-11-03T09:00:00Z")
    private val http = FakeHttp()
    private val source = GitHubReleaseSource(http, "Reveille/0.2.0", Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun sendsTheGitHubHeadersAndTheStoredEtag() = runBlocking {
        http.respond(200, TestReleases.json())
        source.latest(etag = "W/\"abc\"")
        val request = http.requests.single()
        assertEquals("https://api.github.com/repos/replydev/Reveille/releases/latest", request.url)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
        assertEquals("Reveille/0.2.0", request.headers["User-Agent"])
        assertEquals("W/\"abc\"", request.headers["If-None-Match"])
    }

    @Test
    fun omitsIfNoneMatchWithoutAnEtag() = runBlocking {
        http.respond(200, TestReleases.json())
        source.latest(etag = null)
        assertNull(http.requests.single().headers["If-None-Match"])
    }

    @Test
    fun parsesTheReleaseAndItsAssets() = runBlocking {
        http.respond(200, TestReleases.json(extraAssets = """{"name": "notes.txt", "size": 1, "browser_download_url": "x"},"""), mapOf("etag" to "W/\"new\""))
        val result = source.latest(null) as Found
        assertEquals("W/\"new\"", result.etag)
        assertEquals(TestReleases.release("v0.3.0", notes = "## Features\n\n- feat: something new\n"), result.release)
    }

    @Test
    fun notModifiedOn304() = runBlocking {
        http.respond(304, "")
        assertEquals(FetchResult.NotModified, source.latest("W/\"abc\""))
    }

    @Test
    fun noReleaseOn404AndForTagsOutsideTheFormat() = runBlocking {
        http.respond(404, """{"message": "Not Found"}""")
        assertEquals(FetchResult.NoRelease(null), source.latest(null))
        http.respond(200, TestReleases.json(tag = "v0.3.0-rc1"), mapOf("etag" to "e1"))
        assertEquals(FetchResult.NoRelease("e1"), source.latest(null))
    }

    @Test
    fun malformedWhenJsonOrAssetsAreMissing() = runBlocking {
        http.respond(200, "<html>")
        assertEquals(FetchError.Malformed, source.latest(null))
        http.respond(200, TestReleases.json().replace("reveille-v0.3.0.apk.sha256", "checksums.txt"))
        assertEquals(FetchError.Malformed, source.latest(null))
        http.respond(200, TestReleases.json().replace("\"reveille-v0.3.0.apk\"", "\"reveille.apk\""))
        assertEquals(FetchError.Malformed, source.latest(null))
    }

    @Test
    fun rateLimitedOn403WithNoRemainingRequestsOr429() = runBlocking {
        http.respond(403, "{}", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1762164000"))
        assertEquals(FetchError.RateLimited(Instant.ofEpochSecond(1762164000)), source.latest(null))
        http.respond(429, "{}", mapOf("retry-after" to "60"))
        assertEquals(FetchError.RateLimited(now.plusSeconds(60)), source.latest(null))
        http.respond(403, "{}", mapOf("retry-after" to "120"))
        assertEquals(FetchError.RateLimited(now.plusSeconds(120)), source.latest(null))
    }

    @Test
    fun otherStatusesAreHttpErrors() = runBlocking {
        http.respond(403, "{}", mapOf("x-ratelimit-remaining" to "12"))
        assertEquals(FetchError.Http(403), source.latest(null))
        http.respond(502, "")
        assertEquals(FetchError.Http(502), source.latest(null))
    }

    @Test
    fun networkFailuresAreOffline() = runBlocking {
        http.fail(UnknownHostException("api.github.com"))
        assertEquals(FetchError.Offline, source.latest(null))
        http.fail(IOException("reset"))
        assertEquals(FetchError.Offline, source.latest(null))
    }

    class FakeHttp : Http {
        data class Request(val url: String, val headers: Map<String, String>)

        val requests = mutableListOf<Request>()
        private var next: () -> HttpResponse = { error("no response") }

        fun respond(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
            next = { HttpResponse(code, headers, body) }
        }

        fun fail(e: IOException) {
            next = { throw e }
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += Request(url, headers)
            return next()
        }
    }
}
