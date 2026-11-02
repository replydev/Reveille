package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimelineTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val from = ZonedDateTime.of(LocalDate.parse("2026-11-02"), LocalTime.MIDNIGHT, zone).toInstant()
    private val work = Policy(1, "Work", rules = listOf(Rule(10, LocalTime.parse("07:55"), setOf(MONDAY, TUESDAY, WEDNESDAY))))

    @Test
    fun `skipped occurrences stay visible and are flagged`() {
        val snapshot = ScheduleSnapshot(
            policies = listOf(work),
            oneOffs = listOf(OneOff(5, LocalDate.parse("2026-11-03"), LocalTime.parse("10:00"), "Call", OneOffMode.ADDITIVE)),
            skips = listOf(Skip(1, LocalDate.parse("2026-11-03"))),
        )
        val entries = Timeline.build(snapshot, from, zone, days = 3)
        assertEquals(
            listOf("2026-11-02T07:55" to false, "2026-11-03T07:55" to true, "2026-11-03T10:00" to false, "2026-11-04T07:55" to false),
            entries.map { it.occurrence.at.toLocalDateTime().toString() to it.skipped },
        )
    }

    @Test
    fun `timeline covers exactly the requested days`() {
        val entries = Timeline.build(ScheduleSnapshot(policies = listOf(work)), from, zone, days = 7)
        assertEquals(3, entries.size)
    }

    @Test
    fun `skipped policy alarm does not merge with a same-instant one-off`() {
        val tuesday = LocalDate.parse("2026-11-03")
        val snapshot = ScheduleSnapshot(
            policies = listOf(work),
            oneOffs = listOf(OneOff(7, tuesday, LocalTime.parse("07:55"), "Gym", OneOffMode.ADDITIVE)),
            skips = listOf(Skip(1, tuesday)),
        )
        val tuesdayEntries = Timeline.build(snapshot, from, zone, days = 3).filter { it.occurrence.at.toLocalDate() == tuesday }
        assertEquals(
            listOf(Triple("Gym", "O:7", false), Triple("Work", "P:1:10:2026-11-03", true)),
            tuesdayEntries.map { Triple(it.occurrence.label, it.occurrence.key, it.skipped) },
        )
    }

    @Test
    fun `skip on a replaced day shows nothing skipped`() {
        val tuesday = LocalDate.parse("2026-11-03")
        val snapshot = ScheduleSnapshot(
            policies = listOf(work),
            oneOffs = listOf(OneOff(7, tuesday, LocalTime.parse("09:00"), "Late", OneOffMode.REPLACE_DAY)),
            skips = listOf(Skip(1, tuesday)),
        )
        val tuesdayEntries = Timeline.build(snapshot, from, zone, days = 3).filter { it.occurrence.at.toLocalDate() == tuesday }
        assertEquals(listOf("O:7" to false), tuesdayEntries.map { it.occurrence.key to it.skipped })
    }
}
