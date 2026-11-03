package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeValuesTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()
    private val now = at("2026-11-02", "10:00")

    @Test
    fun `day of an instant is today, tomorrow, weekday within a week, then date`() {
        assertEquals(DayLabel.Today, TimeText.dayOf(at("2026-11-02", "23:00"), now, zone))
        assertEquals(DayLabel.Tomorrow, TimeText.dayOf(at("2026-11-03", "07:55"), now, zone))
        assertEquals(DayLabel.Weekday(LocalDate.parse("2026-11-05")), TimeText.dayOf(at("2026-11-05", "08:55"), now, zone))
        assertEquals(DayLabel.Weekday(LocalDate.parse("2026-11-08")), TimeText.dayOf(at("2026-11-08", "08:55"), now, zone))
        assertEquals(DayLabel.Date(LocalDate.parse("2026-11-09")), TimeText.dayOf(at("2026-11-09", "08:00"), now, zone))
    }

    @Test
    fun `day header never uses a bare weekday`() {
        val today = LocalDate.parse("2026-11-02")
        assertEquals(DayLabel.Today, TimeText.dayHeader(today, today))
        assertEquals(DayLabel.Tomorrow, TimeText.dayHeader(today.plusDays(1), today))
        assertEquals(DayLabel.Date(today.plusDays(3)), TimeText.dayHeader(today.plusDays(3), today))
    }

    @Test
    fun `relative keeps the existing rounding`() {
        assertEquals(Relative.Now, TimeText.relativeOf(now.plusSeconds(30), now))
        assertEquals(Relative.Minutes(35), TimeText.relativeOf(now.plus(Duration.ofMinutes(35)), now))
        assertEquals(Relative.Minutes(59), TimeText.relativeOf(now.plus(Duration.ofMinutes(59)), now))
        assertEquals(Relative.Hours(1), TimeText.relativeOf(now.plus(Duration.ofMinutes(60)), now))
        assertEquals(Relative.Hours(19), TimeText.relativeOf(now.plus(Duration.ofHours(19)), now))
        assertEquals(Relative.Days(2), TimeText.relativeOf(now.plus(Duration.ofHours(48)), now))
        assertEquals(Relative.Days(3), TimeText.relativeOf(now.plus(Duration.ofDays(3)), now))
    }

    @Test
    fun `weekday sets are classified`() {
        assertEquals(DaySet.EveryDay, TimeText.daySetOf(DayOfWeek.entries.toSet()))
        assertEquals(DaySet.Weekdays, TimeText.daySetOf(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)))
        assertEquals(DaySet.Custom(listOf(MONDAY, TUESDAY, WEDNESDAY)), TimeText.daySetOf(setOf(WEDNESDAY, MONDAY, TUESDAY)))
    }
}
