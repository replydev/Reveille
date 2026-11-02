package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.SkipEntity
import com.replydev.reveille.data.ReveilleDatabase
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.OneOffMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

// Android 12, 13 and 15: rearm must not use APIs newer than minSdk 31 (LocalDate.ofInstant crashed on 12/13).
@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class AlarmSchedulerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Rome")
    private lateinit var context: Context
    private lateinit var db: ReveilleDatabase
    private lateinit var state: SchedulerState
    private lateinit var repository: ScheduleRepository
    private lateinit var scheduler: AlarmScheduler
    private var now: Instant = rome("2026-11-02", "06:00")

    private fun rome(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private fun triggers(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { it.triggerAtMs }.sorted()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ReveilleDatabase::class.java).allowMainThreadQueries().build()
        state = SchedulerState(PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") })
        repository = ScheduleRepository(db.scheduleDao()) { scheduler.rearm("test change") }
        scheduler = AlarmScheduler(context, repository, db.fireLogDao(), state, Notifier(context), onArmedChanged = {}, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addOneOff(date: String, time: String, label: String): Long =
        repository.saveOneOff(OneOffEntity(date = LocalDate.parse(date), time = LocalTime.parse(time), label = label, mode = OneOffMode.ADDITIVE))

    @Test
    fun armsPrimaryAndBackupForTheNextAlarm() = runBlocking {
        addOneOff("2026-11-05", "09:30", "Dentist")
        val at = rome("2026-11-05", "09:30")
        assertEquals(listOf(at.toEpochMilli(), at.plusSeconds(120).toEpochMilli()), triggers())
        assertEquals("Dentist", state.armed()?.label)
    }

    @Test
    fun cancelsEverythingWhenNothingIsScheduled() = runBlocking {
        val id = addOneOff("2026-11-05", "09:30", "Dentist")
        repository.deleteOneOff(id)
        assertTrue(triggers().isEmpty())
        assertNull(state.armed())
    }

    @Test
    fun rearmsAnUnfiredAlarmForImmediateCatchUp() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        now = rome("2026-11-02", "06:40")
        scheduler.rearm("test")
        assertEquals("Early", state.armed()?.label)
        assertEquals(now.plusSeconds(1).toEpochMilli(), triggers().first())
    }

    @Test
    fun reportsAnAlarmMissedBeyondTheLateWindow() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        now = rome("2026-11-02", "07:01")
        scheduler.rearm("test")
        assertNull(state.armed())
        assertEquals(FireOutcome.MISSED, db.fireLogDao().observeRecent(1).first().single().outcome)
    }

    @Test
    fun claimedAlarmIsNotRungTwiceOrRearmed() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:30")
        assertTrue(scheduler.claim(armed))
        assertFalse(scheduler.claim(armed))
        scheduler.rearm("fired")
        assertNull(state.armed())
        assertTrue(triggers().isEmpty())
    }

    @Test
    fun acknowledgedFallbackIsHandledAndNotRetried() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:31")
        assertTrue(scheduler.claim(armed))
        scheduler.acknowledgeFallback(armed)
        assertEquals(FireOutcome.DISMISSED, scheduler.outcome(armed))
        scheduler.rearm("fallback dismissed")
        assertNull(state.armed())
    }

    @Test
    fun recordOutcomeLogsAnOccurrenceWhoseClaimNeverHappened() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:31")
        scheduler.recordOutcome(armed, FireOutcome.DISMISSED)
        assertEquals(FireOutcome.DISMISSED, scheduler.outcome(armed))
        scheduler.rearm("dismissed")
        assertNull(state.armed())
    }

    @Test
    fun guardIsAnExtraAlarmClockThatLeavesPrimaryAndBackupAlone() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        addOneOff("2026-11-03", "06:30", "Next")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:30")
        scheduler.claim(armed)
        scheduler.rearm("fired")
        val guardAt = now.plusSeconds(90)
        scheduler.armGuard(armed, 0, guardAt)
        val next = rome("2026-11-03", "06:30")
        assertEquals(listOf(guardAt, next, next.plusSeconds(120)).map { it.toEpochMilli() }, triggers())
        val guard = alarms().first { it.triggerAtMs == guardAt.toEpochMilli() }
        val intent = shadowOf(guard.operation).savedIntent
        assertEquals(AlarmScheduler.ACTION_GUARD, intent.action)
        assertEquals("Early", Armed.decode(intent.getStringExtra(AlarmScheduler.EXTRA_ARMED)!!)?.label)
        assertEquals(armed.key, state.guard()?.key)
        // While a guard is armed the system shows it as the next alarm; tapping that opens the ringing screen.
        assertEquals(RingingActivity::class.java.name, shadowOf(guard.showIntent).savedIntent.component?.className)

        scheduler.rearm("again") // rearm never touches the guard
        assertTrue(guardAt.toEpochMilli() in triggers())
        scheduler.cancelGuard(armed)
        assertEquals(listOf(next, next.plusSeconds(120)).map { it.toEpochMilli() }, triggers())
        assertNull(state.guard())
    }

    @Test
    fun guardMovingToANewerAlarmSupersedesTheUnhandledOne() = runBlocking {
        val a = Armed("O:a", rome("2026-11-02", "06:00"), "A")
        val b = Armed("O:b", rome("2026-11-02", "06:05"), "B")
        now = rome("2026-11-02", "06:05")
        scheduler.claim(a)
        scheduler.armGuard(a, 0, now.plusSeconds(90))
        scheduler.claim(b)
        scheduler.armGuard(b, 0, now.plusSeconds(90))
        assertEquals(FireOutcome.SUPERSEDED, scheduler.outcome(a))
        assertEquals(FireOutcome.FIRED, scheduler.outcome(b))
        // The old occurrence no longer owns the guard: it can neither refresh nor cancel it.
        assertFalse(scheduler.refreshGuard(a, now.plusSeconds(150)))
        scheduler.cancelGuard(a)
        assertEquals(b.key, state.guard()?.key)
        assertTrue(scheduler.refreshGuard(b, now.plusSeconds(150)))
        assertEquals(listOf(now.plusSeconds(150).toEpochMilli()), triggers())
    }

    @Test
    fun guardDoesNotSupersedeAnAlreadyHandledOccurrence() = runBlocking {
        val a = Armed("O:a", rome("2026-11-02", "06:00"), "A")
        val b = Armed("O:b", rome("2026-11-02", "06:05"), "B")
        scheduler.claim(a)
        scheduler.armGuard(a, 0, now.plusSeconds(90))
        scheduler.recordOutcome(a, FireOutcome.SNOOZED)
        scheduler.armGuard(b, 1, now.plusSeconds(10))
        assertEquals(FireOutcome.SNOOZED, scheduler.outcome(a))
    }

    @Test
    @Config(shadows = [RefusingAlarmManager::class])
    fun refusedExactAlarmIsNotPersistedAsArmed() = runBlocking {
        addOneOff("2026-11-05", "09:30", "Dentist")
        assertNull(state.armed())
        assertTrue(triggers().isEmpty())
    }

    private suspend fun addDailyPolicy(time: String): Long = repository.savePolicy(
        PolicyEntity(name = "Work"),
        listOf(RuleEntity(policyId = 0, time = LocalTime.parse(time), days = DayOfWeek.entries.toSet(), anchorDate = LocalDate.parse("2026-11-02"))),
        emptyList(),
    )

    @Test
    fun skipNextSkipsAFuturePolicyAlarmForItsDateAndArmsTheNextOne() = runBlocking {
        val policyId = addDailyPolicy("07:00")
        val armed = state.armed()!!
        assertEquals(rome("2026-11-02", "07:00"), armed.at)
        scheduler.skipNext(armed.key, armed.at)
        assertEquals(listOf(SkipEntity(policyId, LocalDate.parse("2026-11-02"))), db.scheduleDao().skips())
        val next = rome("2026-11-03", "07:00")
        assertEquals(next, state.armed()?.at)
        assertEquals(listOf(next, next.plusSeconds(120)).map { it.toEpochMilli() }, triggers())
    }

    @Test
    fun skipNextDeletesAOneOff() = runBlocking {
        addOneOff("2026-11-05", "09:30", "Dentist")
        val armed = state.armed()!!
        scheduler.skipNext(armed.key, armed.at)
        assertTrue(db.scheduleDao().oneOffs().isEmpty())
        assertNull(state.armed())
        assertTrue(triggers().isEmpty())
    }

    @Test
    fun skipNextClearsASnoozeAndCancelsItsDirectAlarm() = runBlocking {
        val snooze = Armed.snooze(now.plusSeconds(540), "Snoozed")
        state.setSnooze(snooze)
        scheduler.rearm("snoozed")
        scheduler.armDirectSnooze(snooze)
        assertEquals(snooze.key, state.armed()?.key)
        scheduler.skipNext(snooze.key, snooze.at)
        assertNull(state.snooze())
        assertNull(state.armed())
        assertTrue(triggers().isEmpty())
    }

    @Test
    fun skipNextOfAPastCatchUpLogsItSkippedSoItIsNotRungAgain() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        addOneOff("2026-11-03", "06:30", "Next")
        now = rome("2026-11-02", "06:40")
        scheduler.rearm("late")
        val catchUp = state.armed()!!
        assertEquals(rome("2026-11-02", "06:30"), catchUp.at)
        scheduler.skipNext(catchUp.key, catchUp.at)
        assertEquals(FireOutcome.SKIPPED, scheduler.outcome(catchUp))
        scheduler.rearm("again")
        assertEquals("Next", state.armed()?.label)
    }

    @Test
    fun staleSkipIsANoOp() = runBlocking {
        addDailyPolicy("07:00")
        val armed = state.armed()!!
        scheduler.skipNext(armed.key, armed.at)
        val next = state.armed()!!
        // A second tap on a widget still showing the first alarm must not skip the following one.
        scheduler.skipNext(armed.key, armed.at)
        assertEquals(next, state.armed())
        assertEquals(1, db.scheduleDao().skips().size)
        // Same key, different time: also stale.
        scheduler.skipNext(next.key, next.at.plusSeconds(60))
        assertEquals(next, state.armed())
    }

    @Test
    fun armedChangedRunsOutsideTheRearmLock() = runBlocking {
        var nested = 0
        lateinit var outer: AlarmScheduler
        outer = AlarmScheduler(context, repository, db.fireLogDao(), state, Notifier(context), onArmedChanged = {
            // Would deadlock if still holding the re-arm mutex.
            if (nested++ == 0) outer.rearm("from callback")
        }, clock = { now })
        kotlinx.coroutines.withTimeout(5_000) { outer.rearm("test") }
        assertEquals(2, nested)
    }

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
}

/** AlarmManager that refuses exact alarms the way a real device does (Robolectric's only records the flag). */
@Implements(AlarmManager::class)
class RefusingAlarmManager : ShadowAlarmManager() {
    @Implementation
    override fun setAlarmClock(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
        throw SecurityException("exact alarms not permitted")
    }
}
