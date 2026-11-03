package com.replydev.reveille.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.Policy
import com.replydev.reveille.engine.Rule
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Skip
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RingTextTest {
    private val labels = Labels(ApplicationProvider.getApplicationContext<Context>().resources, TimeFormats(Locale.UK, is24Hour = true))
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    @Test
    fun paperProgressIsElapsedOverTheRingTime() {
        assertEquals(0.14f, paperProgress(Duration.ofSeconds(42), 5), 0.001f)
        assertEquals(0.5f, paperProgress(Duration.ofMinutes(5), 10), 0.001f)
    }

    @Test
    fun paperProgressIsClamped() {
        assertEquals(0f, paperProgress(Duration.ofSeconds(-3), 5), 0f)
        assertEquals(1f, paperProgress(Duration.ofMinutes(12), 10), 0f)
        assertEquals(1f, paperProgress(Duration.ofMinutes(1), 0), 0f) // never divides by zero
    }

    @Test
    fun elapsedReadsMinutesAndSeconds() {
        assertEquals("0:42", ringElapsed(Duration.ofSeconds(42)))
        assertEquals("12:05", ringElapsed(Duration.ofSeconds(725)))
        assertEquals("0:00", ringElapsed(Duration.ofSeconds(-5)))
    }

    @Test
    fun midnightNamesTheBackupTwoMinutesLater() {
        assertEquals("Backup rings at 05:42", backupRingText(labels, at("2026-11-03", "05:40"), zone))
    }

    private val work = Policy(
        id = 1,
        name = "Work",
        rules = listOf(Rule(id = 10, time = LocalTime.of(7, 55), days = setOf(MONDAY, TUESDAY, WEDNESDAY))),
    )
    private val gym = Policy(id = 2, name = "Gym", rules = listOf(Rule(id = 20, time = LocalTime.of(6, 0), days = setOf(TUESDAY))))
    private val snapshot = ScheduleSnapshot(listOf(work, gym), listOf(OneOff(5, LocalDate.parse("2026-11-03"), LocalTime.of(9, 0))))
    private val tuesdayAlarm = Armed("P:1:10:2026-11-03", at("2026-11-03", "07:55"), "Work", listOf("P:1:10:2026-11-03"))

    @Test
    fun policyInfoHasTheNameRuleDaysAndNextOccurrence() {
        val info = policyRingInfo(snapshot, tuesdayAlarm, now = at("2026-11-03", "07:55"), zone = zone)!!
        assertEquals("Work", info.name)
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY), info.days)
        assertEquals(at("2026-11-04", "07:55"), info.next)
    }

    @Test
    fun policyInfoNextSkipsSkippedDays() {
        val skipped = snapshot.copy(skips = listOf(Skip(1, LocalDate.parse("2026-11-04"))))
        assertEquals(at("2026-11-09", "07:55"), policyRingInfo(skipped, tuesdayAlarm, at("2026-11-03", "07:55"), zone)!!.next)
    }

    @Test
    fun policyInfoForASnoozeLooksAfterNow() {
        val snooze = Armed.snooze(at("2026-11-03", "08:04"), "Work", tuesdayAlarm.sourceKeys)
        val info = policyRingInfo(snapshot, snooze, now = at("2026-11-03", "08:04"), zone = zone)!!
        assertEquals("Work", info.name)
        assertEquals(at("2026-11-04", "07:55"), info.next)
    }

    @Test
    fun policyInfoWithoutAnotherOccurrenceHasNoNext() {
        val ending = snapshot.copy(policies = listOf(work.copy(endDate = LocalDate.parse("2026-11-03"))))
        assertNull(policyRingInfo(ending, tuesdayAlarm, at("2026-11-03", "07:55"), zone)!!.next)
    }

    @Test
    fun policyInfoIsNullForOneOffsAndDeletedPolicies() {
        assertNull(policyRingInfo(snapshot, Armed("O:5", at("2026-11-03", "09:00"), "Alarm", listOf("O:5")), at("2026-11-03", "09:00"), zone))
        assertNull(policyRingInfo(snapshot.copy(policies = listOf(gym)), tuesdayAlarm, at("2026-11-03", "07:55"), zone))
    }
}
