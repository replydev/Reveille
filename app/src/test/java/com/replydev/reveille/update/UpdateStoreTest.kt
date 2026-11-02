package com.replydev.reveille.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class UpdateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dataStore by lazy { PreferenceDataStoreFactory.create { File(tmp.root, "updates.preferences_pb") } }
    private val store by lazy { UpdateStore(dataStore) }

    @Test
    fun defaults() = runBlocking {
        assertEquals(UpdatePrefs(), store.current())
        assertEquals(true, store.current().autoCheck)
        assertEquals(true, store.current().notify)
    }

    @Test
    fun recordsACheckAtomicallyAndRoundTripsTheRelease() = runBlocking {
        val at = Instant.parse("2026-11-03T09:12:00Z")
        val release = TestReleases.release("v0.3.0", notes = "## Fixes\n\n- fix: \"quoted\" ünïcode\n")
        store.recordCheck(at, etag = "W/\"e\"", latest = release)
        val prefs = store.current()
        assertEquals(at, prefs.lastCheckedAt)
        assertEquals("W/\"e\"", prefs.etag)
        assertEquals(release, prefs.latest)

        store.recordCheck(at.plusSeconds(60), etag = null, latest = null)
        assertNull(store.current().latest)
        assertNull(store.current().etag)
    }

    @Test
    fun notModifiedOnlyMovesTheCheckTime() = runBlocking {
        val release = TestReleases.release("v0.3.0")
        store.recordCheck(Instant.EPOCH, "e", release)
        val at = Instant.parse("2026-11-03T09:12:00Z")
        store.recordNotModified(at)
        assertEquals(UpdatePrefs(lastCheckedAt = at, etag = "e", latest = release), store.current())
    }

    @Test
    fun togglesSkipsDismissalsRemindersAndNotifications() = runBlocking {
        store.setAutoCheck(false)
        store.setNotify(false)
        store.skip(3_000)
        store.dismiss(3_000)
        store.markNotified(3_000)
        val remind = Instant.parse("2026-11-04T06:35:00Z")
        store.setRemindAfter(remind)
        assertEquals(
            UpdatePrefs(
                autoCheck = false,
                notify = false,
                skippedVersionCode = 3_000,
                dismissedVersionCode = 3_000,
                notifiedVersionCode = 3_000,
                remindAfter = remind,
            ),
            store.current(),
        )
        store.setRemindAfter(null)
        assertNull(store.current().remindAfter)
    }

    @Test
    fun anUnreadableStoredReleaseAlsoDropsTheEtagSoTheNextCheckRefetches() = runBlocking {
        store.recordCheck(Instant.EPOCH, "e", TestReleases.release("v0.3.0"))
        dataStore.edit { it[stringPreferencesKey("latest")] = "{not json" }
        val prefs = store.current()
        assertNull(prefs.latest)
        assertNull(prefs.etag)
    }
}
