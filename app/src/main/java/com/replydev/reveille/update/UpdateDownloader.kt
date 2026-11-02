package com.replydev.reveille.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult

    data class Failed(val reason: FailReason) : DownloadResult
}

/**
 * Downloads a release's APK into [dir] (`cacheDir/updates/`), hashing it while it streams. The `.sha256` asset is read
 * first: without a checksum to compare against, the APK is not even downloaded. Anything that isn't a complete file
 * with the published SHA-256 is deleted, including on cancellation.
 */
class UpdateDownloader(
    private val dir: File,
    private val http: Http,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : ApkDownloader {
    override suspend fun download(release: Release, onProgress: (Float) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        clear()
        val expected = try {
            val response = http.get(release.sha256Url, mapOf("User-Agent" to USER_AGENT))
            if (response.code != 200) return@withContext DownloadResult.Failed(FailReason.DOWNLOAD)
            Sha256File.parse(response.body) ?: return@withContext DownloadResult.Failed(FailReason.CHECKSUM)
        } catch (_: IOException) {
            return@withContext DownloadResult.Failed(FailReason.DOWNLOAD)
        }

        dir.mkdirs()
        val part = File(dir, "reveille-${release.tag}.apk.part")
        var done = false
        try {
            val actual = try {
                stream(release, part, onProgress) ?: return@withContext DownloadResult.Failed(FailReason.DOWNLOAD)
            } catch (_: IOException) {
                return@withContext DownloadResult.Failed(FailReason.DOWNLOAD)
            }
            if (actual != expected) return@withContext DownloadResult.Failed(FailReason.CHECKSUM)
            val apk = File(dir, "reveille-${release.tag}.apk")
            if (!part.renameTo(apk)) return@withContext DownloadResult.Failed(FailReason.DOWNLOAD)
            done = true
            DownloadResult.Ok(apk)
        } finally {
            if (!done) part.delete()
        }
    }

    /** Deletes every downloaded or partial APK. */
    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Streams the APK into [part] and returns its SHA-256, or null if the server didn't send a complete file. */
    private suspend fun stream(release: Release, part: File, onProgress: (Float) -> Unit): String? {
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode != 200) return null
            val declared = connection.contentLengthLong
            val total = if (declared > 0) declared else release.apkSize
            if (total > MAX_BYTES) return null
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > MAX_BYTES) return null
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        val percent = if (total > 0) (read * 100 / total).toInt().coerceAtMost(100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
            if (declared > 0 && read != declared) return null
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "Reveille"
        const val BUFFER = 8 * 1024

        /** Releases are about 30 MB; anything far bigger is not ours. */
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
