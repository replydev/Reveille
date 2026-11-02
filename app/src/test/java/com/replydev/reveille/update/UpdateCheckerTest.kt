package com.replydev.reveille.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.update.CheckOutcome.Available
import com.replydev.reveille.update.CheckOutcome.Failed
import com.replydev.reveille.update.CheckOutcome.UpToDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class UpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-03T09:12:00Z")
    private val v030 = TestReleases.release("v0.3.0")
    private val source = FakeReleaseSource(FetchResult.Found(v030, "e1"))
    private val notified = mutableListOf<Release>()
    private val store by lazy { UpdateStore(PreferenceDataStoreFactory.create { File(tmp.root, "u.preferences_pb") }) }

    private fun checker(installed: Int = 2_000) =
        UpdateChecker(store, source, installed, Clock.fixed(now, ZoneOffset.UTC)) { notified += it }

    @Test
    fun aNewVersionIsStoredAndNotifiedOnce() = runBlocking {
        assertEquals(Available(v030), checker().check(manual = false))
        assertEquals(listOf(v030), notified)
        val prefs = store.current()
        assertEquals(v030, prefs.latest)
        assertEquals("e1", prefs.etag)
        assertEquals(now, prefs.lastCheckedAt)
        assertEquals(3_000, prefs.notifiedVersionCode)

        assertEquals(Available(v030), checker().check(manual = false))
        assertEquals(1, notified.size)
    }

    @Test
    fun aManualCheckDoesNotNotifyButCountsAsSeen() = runBlocking {
        assertEquals(Available(v030), checker().check(manual = true))
        assertTrue(notified.isEmpty())
        checker().check(manual = false)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun theSameVersionIsUpToDate() = runBlocking {
        assertEquals(UpToDate, checker(installed = 3_000).check(manual = false))
        assertTrue(notified.isEmpty())
        assertEquals(v030, store.current().latest)
    }

    @Test
    fun aSkippedVersionIsAvailableButNotNotified() = runBlocking {
        store.skip(3_000)
        assertEquals(Available(v030), checker().check(manual = false))
        assertTrue(notified.isEmpty())
    }

    @Test
    fun notModifiedSendsTheEtagAndKeepsTheStoredRelease() = runBlocking {
        checker().check(manual = true)
        source.next = FetchResult.NotModified
        val later = UpdateChecker(store, source, 2_000, Clock.fixed(now.plusSeconds(86_400), ZoneOffset.UTC)) { notified += it }
        assertEquals(Available(v030), later.check(manual = false))
        assertEquals("e1", source.lastEtag)
        assertEquals(now.plusSeconds(86_400), store.current().lastCheckedAt)
    }

    @Test
    fun noReleaseClearsTheStoredOne() = runBlocking {
        checker().check(manual = true)
        source.next = FetchResult.NoRelease("e2")
        assertEquals(UpToDate, checker().check(manual = true))
        assertNull(store.current().latest)
        assertEquals("e2", store.current().etag)
    }

    @Test
    fun failuresKeepTheLastGoodStateAndDoNotMoveLastChecked() = runBlocking {
        checker().check(manual = true)
        for (error in listOf(FetchError.RateLimited(null), FetchError.Offline, FetchError.Http(502), FetchError.Malformed)) {
            source.next = error
            val later = UpdateChecker(store, source, 2_000, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)) { }
            assertEquals(Failed(error), later.check(manual = true))
            assertEquals(now, store.current().lastCheckedAt)
            assertEquals(v030, store.current().latest)
        }
    }

    @Test
    fun aBackgroundCheckIsSkippedWhileAutoCheckIsOff() = runBlocking {
        store.setAutoCheck(false)
        assertEquals(CheckOutcome.Disabled, checker().check(manual = false))
        assertEquals(0, source.calls)
        assertEquals(Available(v030), checker().check(manual = true))
    }
}
