package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.data.ReveilleDatabase
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
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
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
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
    fun unclaimLetsALaterDeliveryClaimAndCatchesUp() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:30")
        assertTrue(scheduler.claim(armed))
        scheduler.unclaim(armed)
        scheduler.rearm("ringing failed")
        // Released occurrence is retried as a catch-up right away, and a later delivery can claim it.
        assertEquals("Early", state.armed()?.label)
        assertEquals(now.plusSeconds(1).toEpochMilli(), triggers().first())
        assertTrue(scheduler.claim(armed))
    }

    @Test
    fun unclaimedOccurrenceIsReportedMissedAfterTheLateWindow() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:30")
        scheduler.claim(armed)
        scheduler.unclaim(armed)
        scheduler.rearm("ringing failed")
        now = rome("2026-11-02", "07:01")
        scheduler.rearm("ringing failed again")
        assertNull(state.armed())
        assertEquals(FireOutcome.MISSED, db.fireLogDao().observeRecent(1).first().single().outcome)
    }

    @Test
    fun unclaimKeepsAUserOutcomeAndAcknowledgedFallbackIsNotRetried() = runBlocking {
        addOneOff("2026-11-02", "06:30", "Early")
        val armed = state.armed()!!
        now = rome("2026-11-02", "06:31")
        assertTrue(scheduler.claim(armed))
        scheduler.acknowledgeFallback(armed)
        scheduler.unclaim(armed)
        assertEquals(FireOutcome.DISMISSED, db.fireLogDao().observeRecent(1).first().single().outcome)
        scheduler.rearm("fallback dismissed")
        assertNull(state.armed())
    }
}
