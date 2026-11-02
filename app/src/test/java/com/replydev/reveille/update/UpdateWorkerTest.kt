package com.replydev.reveille.update

import android.content.Context
import android.os.UserManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UpdateWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun periodic(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(UpdateWorker.PERIODIC).get()
        .filter { it.state != WorkInfo.State.CANCELLED }

    private fun reminder(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(UpdateWorker.REMIND).get()
        .filter { it.state != WorkInfo.State.CANCELLED }

    @Test
    fun autoCheckEnqueuesADailyNetworkBoundCheckAndOffCancelsIt() {
        UpdateWorker.sync(context, autoCheck = true)
        val info = periodic().single()
        assertEquals(TimeUnit.HOURS.toMillis(24), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        UpdateWorker.sync(context, autoCheck = true) // kept, not duplicated
        assertEquals(1, periodic().size)

        UpdateWorker.sync(context, autoCheck = false)
        assertTrue(periodic().isEmpty())
    }

    @Test
    fun nothingIsEnqueuedWhileTheUserIsLocked() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        UpdateWorker.sync(context, autoCheck = true)
        UpdateWorker.scheduleRemind(context, Instant.now().plusSeconds(600))
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        assertTrue(periodic().isEmpty())
        assertTrue(reminder().isEmpty())
    }

    @Test
    fun theReminderIsAOneOffJobAtRemindAfterAndReplacesTheLastOne() {
        val now = Instant.parse("2026-11-04T06:00:00Z")
        UpdateWorker.scheduleRemind(context, now.plusSeconds(3_600), now)
        UpdateWorker.scheduleRemind(context, now.plusSeconds(1_800), now)
        val info = reminder().single()
        assertEquals(Duration.ofMinutes(30).toMillis(), info.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        UpdateWorker.cancelRemind(context)
        assertTrue(reminder().isEmpty())
    }

    // The work itself, without WorkManager.

    private val now = Instant.parse("2026-11-04T06:35:00Z")
    private val store by lazy { UpdateStore(PreferenceDataStoreFactory.create { File(tmp.root, "u.preferences_pb") }) }
    private val posted = mutableListOf<Release>()
    private val source = FakeReleaseSource(FetchResult.Found(TestReleases.release("v0.3.0"), "e"))

    private fun work() = UpdateWork(
        store,
        UpdateChecker(store, source, 2_000, Clock.fixed(now, ZoneOffset.UTC)) { posted += it },
        installedVersionCode = 2_000,
        notify = { posted += it },
    )

    @Test
    fun theDailyRunChecksInTheBackground() = runBlocking {
        assertEquals(CheckOutcome.Available(TestReleases.release("v0.3.0")), work().daily())
        assertEquals(1, source.calls)
        assertEquals(1, posted.size)
    }

    @Test
    fun theReminderReNotifiesTheStoredReleaseWithoutTheNetworkAndClearsRemindAfter() = runBlocking {
        work().daily()
        posted.clear()
        store.setRemindAfter(now)
        work().remind()
        assertEquals(listOf(TestReleases.release("v0.3.0")), posted)
        assertEquals(1, source.calls)
        assertNull(store.current().remindAfter)
    }

    @Test
    fun theReminderStaysQuietOnceSkippedOrInstalled() = runBlocking {
        work().daily()
        posted.clear()
        store.skip(3_000)
        work().remind()
        assertTrue(posted.isEmpty())

        store.recordCheck(now, null, null)
        work().remind()
        assertTrue(posted.isEmpty())
    }
}
