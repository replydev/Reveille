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

class TimeTextTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()
    private val now = at("2026-11-02", "10:00")

    @Test
    fun `day and time uses today, tomorrow, weekday, then date`() {
        assertEquals("Today 23:00", TimeText.dayAndTime(at("2026-11-02", "23:00"), now, zone))
        assertEquals("Tomorrow 07:55", TimeText.dayAndTime(at("2026-11-03", "07:55"), now, zone))
        assertEquals("Thu 08:55", TimeText.dayAndTime(at("2026-11-05", "08:55"), now, zone))
        assertEquals("Wed 18 Nov 08:00", TimeText.dayAndTime(at("2026-11-18", "08:00"), now, zone))
    }

    @Test
    fun `relative time rounds to a readable unit`() {
        assertEquals("now", TimeText.relative(now.plusSeconds(30), now))
        assertEquals("in 35 min", TimeText.relative(now.plus(Duration.ofMinutes(35)), now))
        assertEquals("in 19 h", TimeText.relative(now.plus(Duration.ofHours(19)), now))
        assertEquals("in 3 d", TimeText.relative(now.plus(Duration.ofDays(3)), now))
    }

    @Test
    fun `weekday sets are summarised`() {
        assertEquals("Every day", TimeText.days(DayOfWeek.entries.toSet()))
        assertEquals("Mon–Fri", TimeText.days(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)))
        assertEquals("Mon Tue Wed", TimeText.days(setOf(WEDNESDAY, MONDAY, TUESDAY)))
    }

    @Test
    fun `headers, dates and times`() {
        val today = LocalDate.parse("2026-11-02")
        assertEquals("Today", TimeText.dayHeader(today, today))
        assertEquals("Tomorrow", TimeText.dayHeader(today.plusDays(1), today))
        assertEquals("Thu 5 Nov", TimeText.dayHeader(today.plusDays(3), today))
        assertEquals("5 Nov 2026", TimeText.date(LocalDate.parse("2026-11-05")))
        assertEquals("07:05", TimeText.time(LocalTime.of(7, 5)))
    }
}
