package com.replydev.reveille.ui.home

import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Occurrence
import com.replydev.reveille.engine.Source
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class NextUpTest {
    private val now = Instant.parse("2026-11-02T06:00:00Z")
    private fun at(minutes: Long) = now.plusSeconds(minutes * 60)
    private fun occurrence(minutes: Long, id: Long = 1) =
        Occurrence(at(minutes).atZone(ZoneOffset.UTC), "Alarm $id", listOf(Source.OneOffAlarm(id)))
    private fun armed(minutes: Long, id: Long = 1) = Armed("O:$id", at(minutes), "Alarm $id", listOf("O:$id"))

    @Test
    fun nothingScheduled() {
        assertEquals(NextUp.None, nextUp(null, null, now))
    }

    @Test
    fun armedMatchingTheSchedule() {
        assertEquals(NextUp.Set(armed(60), earlierUnarmed = null), nextUp(armed(60), occurrence(60), now))
    }

    @Test
    fun scheduleHasAnEarlierAlarmThanTheArmedOne() {
        assertEquals(NextUp.Set(armed(60), earlierUnarmed = occurrence(30, 2)), nextUp(armed(60), occurrence(30, 2), now))
    }

    @Test
    fun snoozeIsNotFlaggedAgainstAnEarlierScheduledAlarm() {
        val snooze = Armed.snooze(at(60), "Work")
        assertEquals(NextUp.Set(snooze, earlierUnarmed = null), nextUp(snooze, occurrence(30), now))
    }

    @Test
    fun armedInThePastIsOverdueNotNext() {
        assertEquals(NextUp.Overdue(armed(-2)), nextUp(armed(-2), occurrence(60), now))
        assertEquals(NextUp.Overdue(armed(0)), nextUp(armed(0), null, now))
    }

    @Test
    fun scheduledButNothingArmed() {
        assertEquals(NextUp.NotSet(occurrence(30)), nextUp(null, occurrence(30), now))
    }
}
