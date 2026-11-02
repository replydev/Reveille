package com.replydev.reveille.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class UpdateDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val apk = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val dir by lazy { tmp.newFolder("updates") }
    private val downloader by lazy { UpdateDownloader(dir, UrlConnectionHttp()) }
    private val release by lazy {
        TestReleases.release("v0.3.0").copy(apkUrl = "$base/download/apk", sha256Url = "$base/download/sha", apkSize = apk.size.toLong())
    }

    @After
    fun tearDown() = server.stop(0)

    private fun serve(path: String, body: ByteArray, code: Int = 200) = server.createContext(path) { ex ->
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** GitHub answers asset URLs with a redirect to its storage host. */
    private fun redirect(path: String, to: String) = server.createContext(path) { ex ->
        ex.responseHeaders.add("Location", "$base$to")
        ex.sendResponseHeaders(302, -1)
        ex.close()
    }

    @Test
    fun downloadsFollowsRedirectsVerifiesTheChecksumAndReportsProgress() = runBlocking {
        redirect("/download/apk", "/storage/apk")
        redirect("/download/sha", "/storage/sha")
        serve("/storage/apk", apk)
        serve("/storage/sha", "$sha  reveille-v0.3.0.apk\n".toByteArray())
        val progress = mutableListOf<Float>()

        val result = downloader.download(release) { progress += it }

        val file = (result as DownloadResult.Ok).file
        assertArrayEquals(apk, file.readBytes())
        assertEquals("reveille-v0.3.0.apk", file.name)
        assertEquals(listOf(file), dir.listFiles()!!.toList())
        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size > 2)
    }

    @Test
    fun aChecksumMismatchDeletesTheFile() = runBlocking {
        serve("/download/apk", apk)
        serve("/download/sha", "${"0".repeat(64)}  reveille-v0.3.0.apk".toByteArray())
        assertEquals(DownloadResult.Failed(FailReason.CHECKSUM), downloader.download(release) {})
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun anUnreadableChecksumFileNeverDownloadsTheApk() = runBlocking {
        var apkRequested = false
        server.createContext("/download/apk") { ex -> apkRequested = true; ex.sendResponseHeaders(500, -1); ex.close() }
        serve("/download/sha", "<html>oops</html>".toByteArray())
        assertEquals(DownloadResult.Failed(FailReason.CHECKSUM), downloader.download(release) {})
        assertFalse(apkRequested)
    }

    @Test
    fun httpErrorsAndAMissingServerAreDownloadFailures() = runBlocking {
        serve("/download/sha", "$sha  x".toByteArray())
        serve("/download/apk", "gone".toByteArray(), code = 404)
        assertEquals(DownloadResult.Failed(FailReason.DOWNLOAD), downloader.download(release) {})
        assertTrue(dir.listFiles()!!.isEmpty())

        val offline = release.copy(sha256Url = "http://127.0.0.1:1/sha")
        assertEquals(DownloadResult.Failed(FailReason.DOWNLOAD), downloader.download(offline) {})
    }

    @Test
    fun aTruncatedDownloadIsAFailure() = runBlocking {
        serve("/download/sha", "$sha  x".toByteArray())
        server.createContext("/download/apk") { ex ->
            ex.sendResponseHeaders(200, apk.size.toLong())
            ex.responseBody.write(apk, 0, 1000)
            ex.close() // connection drops mid-body
        }
        val result = downloader.download(release) {}
        assertTrue(result is DownloadResult.Failed)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellingDeletesThePartialFile() = runBlocking {
        serve("/download/sha", "$sha  x".toByteArray())
        val started = CountDownLatch(1)
        val release2 = CountDownLatch(1)
        server.createContext("/download/apk") { ex ->
            ex.sendResponseHeaders(200, apk.size.toLong())
            ex.responseBody.write(apk, 0, 100_000)
            ex.responseBody.flush()
            started.countDown()
            release2.await(5, TimeUnit.SECONDS)
            runCatching { ex.responseBody.write(apk, 100_000, apk.size - 100_000); ex.close() }
        }
        val job = async(Dispatchers.IO) { downloader.download(release) {} }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        job.cancel()
        release2.countDown()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        (job as Job).join()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun startingOverClearsOldDownloads() = runBlocking {
        dir.resolve("reveille-v0.2.9.apk").writeText("old")
        dir.resolve("reveille-v0.3.0.apk.part").writeText("half")
        serve("/download/apk", apk)
        serve("/download/sha", sha.toByteArray())
        val file = (downloader.download(release) {} as DownloadResult.Ok).file
        assertEquals(listOf(file), dir.listFiles()!!.toList())
    }
}
