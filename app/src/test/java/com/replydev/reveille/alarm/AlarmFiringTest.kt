package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.ReveilleDatabase
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.data.SettingsStore
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Duration
import java.time.Instant

@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class AlarmFiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: ReveilleDatabase
    private lateinit var state: SchedulerState
    private lateinit var scheduler: AlarmScheduler
    private lateinit var firing: AlarmFiring
    private var now: Instant = Instant.parse("2026-11-02T06:30:00Z")
    private val armed = Armed("O:1", Instant.parse("2026-11-02T06:30:00Z"), "Dentist")

    /** What the fake service start does; records every attempt. */
    private var start: (Armed, Boolean, Boolean) -> Boolean = { _, _, _ -> true }
    private val starts = mutableListOf<Triple<Armed, Boolean, Boolean>>()
    private var ringing: Armed? = null
    private var dismissSent = 0

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ReveilleDatabase::class.java).allowMainThreadQueries().build()
        val prefs = PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") }
        state = SchedulerState(prefs)
        val notifier = Notifier(context).also { it.createChannels() }
        lateinit var repository: ScheduleRepository
        repository = ScheduleRepository(db.scheduleDao()) { scheduler.rearm("test change") }
        scheduler = AlarmScheduler(context, repository, db.fireLogDao(), state, notifier, onArmedChanged = {}, clock = { now })
        firing = AlarmFiring(
            scheduler,
            state,
            SettingsStore(prefs),
            notifier,
            startService = { a, backup, guard -> starts += Triple(a, backup, guard); start(a, backup, guard) },
            ringingNow = { ringing },
            dismissRinging = { dismissSent++ },
            clock = { now },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun fallbackPosted() = notifications().any { it.channelId == Notifier.CHANNEL_ALARM_FALLBACK }

    private fun missedPosted() = notifications().any { it.extras.getString("android.title") == "Missed alarm" }

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    /** The armed guard alarm as (trigger, attempt), or null. */
    private fun guard(): Pair<Instant, Int>? = alarms()
        .map { it.triggerAtMs to shadowOf(it.operation).savedIntent }
        .singleOrNull { it.second.action == AlarmScheduler.ACTION_GUARD }
        ?.let { (at, intent) -> Instant.ofEpochMilli(at) to intent.getIntExtra(AlarmScheduler.EXTRA_ATTEMPT, -1) }

    @Test
    fun startThatThrowsIsReportedAsFailure() {
        start = { _, _, _ -> throw IllegalStateException("background start not allowed") }
        assertEquals(false, firing.startRinging(armed, backup = false, guard = false))
    }

    @Test
    fun refusedStartClaimsPostsFallbackAndArmsGuard() = runBlocking {
        firing.onStartFailed(armed)
        assertEquals(FireOutcome.FIRED, scheduler.outcome(armed))
        assertTrue(fallbackPosted())
        assertEquals(now.plusSeconds(10) to 1, guard())
        assertEquals(armed.key, state.guard()?.key)
    }

    @Test
    fun refusedStartOfAnAlreadyHandledOccurrenceDoesNothing() = runBlocking {
        scheduler.claim(armed)
        scheduler.recordOutcome(armed, FireOutcome.DISMISSED)
        firing.onStartFailed(armed)
        assertTrue(notifications().isEmpty())
        assertNull(guard())
    }

    @Test
    fun refusedStartConsumesTheSnoozeItRings() = runBlocking {
        val snooze = Armed.snooze(now, "Dentist")
        state.setSnooze(snooze)
        firing.onStartFailed(snooze)
        assertNull(state.snooze())
        assertEquals(snooze.key, state.guard()?.key)
    }

    @Test
    fun guardOfAnUnhandledOccurrenceStartsTheServiceInGuardMode() = runBlocking {
        scheduler.claim(armed)
        now = now.plusSeconds(90)
        firing.onGuard(armed, attempt = 0)
        assertEquals(listOf(Triple(armed, false, true)), starts)
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun guardThatCannotStartTheServicePostsFallbackAndBacksOff() = runBlocking {
        start = { _, _, _ -> false }
        scheduler.claim(armed)
        now = now.plusSeconds(10)
        firing.onGuard(armed, attempt = 1)
        assertTrue(fallbackPosted())
        assertEquals(now.plusSeconds(30) to 2, guard())
        firing.onGuard(armed, attempt = 2)
        assertEquals(now.plusSeconds(60) to 3, guard())
        firing.onGuard(armed, attempt = 3)
        assertEquals(now.plusSeconds(60) to 4, guard())
    }

    @Test
    fun guardWhileTheOccurrenceRingsInThisProcessDoesNothing() = runBlocking {
        scheduler.claim(armed)
        ringing = armed
        firing.onGuard(armed, attempt = 0)
        assertTrue(starts.isEmpty())
    }

    @Test
    fun guardBeyondTheLateWindowReportsMissed() = runBlocking {
        scheduler.claim(armed)
        scheduler.armGuard(armed, 0, now.plusSeconds(90))
        now = armed.at.plus(Duration.ofMinutes(31))
        firing.onGuard(armed, attempt = 0)
        assertTrue(starts.isEmpty())
        assertEquals(FireOutcome.MISSED, scheduler.outcome(armed))
        assertTrue(missedPosted())
        assertNull(guard())
        assertNull(state.guard())
    }

    @Test
    fun guardOfAHandledOccurrenceDoesNothing() = runBlocking {
        for (outcome in listOf(FireOutcome.DISMISSED, FireOutcome.SNOOZED, FireOutcome.SUPERSEDED, FireOutcome.MISSED)) {
            scheduler.recordOutcome(armed, outcome)
            firing.onGuard(armed, attempt = 0)
            assertEquals(outcome, scheduler.outcome(armed))
        }
        assertTrue(starts.isEmpty())
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun duplicateDeliveryDoesNotRingTwice() = runBlocking {
        assertEquals(Delivery.RING, firing.decideDelivery(armed, guard = false))
        assertEquals(Delivery.DUPLICATE, firing.decideDelivery(armed, guard = false)) // backup / catch-up
        assertEquals(Delivery.RING, firing.decideDelivery(armed, guard = true)) // still FIRED: guard re-rings
        firing.dismiss(armed)
        assertEquals(Delivery.DUPLICATE, firing.decideDelivery(armed, guard = true))
        assertEquals(Delivery.DUPLICATE, firing.decideDelivery(armed, guard = false))
    }

    @Test
    fun ringStartGuardsResetsAutoSnoozesAndRearms() = runBlocking {
        state.setAutoSnoozeCount(2)
        firing.decideDelivery(armed, guard = false)
        firing.onRingStarted(armed)
        assertEquals(now.plusSeconds(90) to 0, guard())
        assertEquals(0, state.autoSnoozeCount())
        now = now.plusSeconds(60)
        assertTrue(firing.refreshGuard(armed))
        assertEquals(now.plusSeconds(90) to 0, guard())
    }

    @Test
    fun snoozeIsPersistedAndCancelsTheGuard() = runBlocking {
        firing.decideDelivery(armed, guard = false)
        firing.onRingStarted(armed)
        firing.snooze(armed, auto = false)
        assertEquals(FireOutcome.SNOOZED, scheduler.outcome(armed))
        val snooze = state.snooze()!!
        assertEquals(now.plus(Duration.ofMinutes(9)), snooze.at)
        assertEquals(snooze.key, state.armed()?.key) // the snooze is armed with AlarmManager
        assertNull(guard())
    }

    @Test
    fun autoSnoozeGivesUpAsMissedAfterThreeInARow() = runBlocking {
        state.setAutoSnoozeCount(RingDecisions.MAX_AUTO_SNOOZES)
        val snooze = Armed.snooze(now, "Dentist")
        firing.decideDelivery(snooze, guard = false)
        firing.snooze(snooze, auto = true)
        assertEquals(FireOutcome.MISSED, scheduler.outcome(snooze))
        assertTrue(missedPosted())
        assertEquals(0, state.autoSnoozeCount())
        assertNull(state.snooze())
    }

    @Test
    fun dismissIsPersistedAndCancelsTheGuard() = runBlocking {
        state.setAutoSnoozeCount(1)
        firing.decideDelivery(armed, guard = false)
        firing.onRingStarted(armed)
        firing.dismiss(armed)
        assertEquals(FireOutcome.DISMISSED, scheduler.outcome(armed))
        assertEquals(0, state.autoSnoozeCount())
        assertNull(guard())
    }

    @Test
    fun dismissingTheFallbackAcknowledgesAndCancelsEverything() = runBlocking {
        firing.onStartFailed(armed)
        ringing = armed // the guard managed to start the service meanwhile
        firing.onFallbackDismissed(armed)
        assertEquals(FireOutcome.DISMISSED, scheduler.outcome(armed))
        assertTrue(notifications().isEmpty())
        assertNull(guard())
        assertEquals(1, dismissSent)
    }
}
