package com.replydev.reveille.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.DayLabel
import com.replydev.reveille.engine.DaySet
import com.replydev.reveille.engine.Relative
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LabelsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()
    private val now = at("2026-11-02", "10:00")

    @Test
    fun `days render as words, weekdays or dates`() {
        assertEquals("Today", labels.day(DayLabel.Today))
        assertEquals("Tomorrow", labels.day(DayLabel.Tomorrow))
        assertEquals("Thu", labels.day(DayLabel.Weekday(LocalDate.parse("2026-11-05"))))
        assertEquals("Wed, 18 Nov", labels.day(DayLabel.Date(LocalDate.parse("2026-11-18"))))
    }

    @Test
    fun `relative times use plurals`() {
        assertEquals("now", labels.relative(Relative.Now))
        assertEquals("in 1 min", labels.relative(Relative.Minutes(1)))
        assertEquals("in 35 min", labels.relative(Relative.Minutes(35)))
        assertEquals("in 19 h", labels.relative(Relative.Hours(19)))
        assertEquals("in 3 d", labels.relative(Relative.Days(3)))
    }

    @Test
    fun `day sets`() {
        assertEquals("Every day", labels.days(DaySet.EveryDay))
        assertEquals("Mon–Fri", labels.days(DaySet.Weekdays))
        assertEquals("Mon Tue Wed", labels.days(DaySet.Custom(listOf(MONDAY, TUESDAY, WEDNESDAY))))
    }

    @Test
    fun `day and time matches the old engine output`() {
        assertEquals("Today 23:00", labels.dayAndTime(at("2026-11-02", "23:00"), now, zone))
        assertEquals("Tomorrow 07:55", labels.dayAndTime(at("2026-11-03", "07:55"), now, zone))
        assertEquals("Thu 08:55", labels.dayAndTime(at("2026-11-05", "08:55"), now, zone))
        assertEquals("Wed, 18 Nov 08:00", labels.dayAndTime(at("2026-11-18", "08:00"), now, zone))
    }

    @Test
    fun `day part drops the time from the day and time text`() {
        val now = Instant.parse("2026-11-02T20:00:00Z")
        assertEquals("Tomorrow", labels.dayPart(Instant.parse("2026-11-03T06:55:00Z"), now, zone))
        assertEquals("Today", labels.dayPart(Instant.parse("2026-11-02T21:30:00Z"), now, zone))
        assertEquals("Thu", labels.dayPart(Instant.parse("2026-11-05T06:55:00Z"), now, zone))
    }

    @Test
    fun `dayHeader matches the old engine output`() {
        val today = LocalDate.parse("2026-11-02")
        assertEquals("Today", labels.dayHeader(today, today))
        assertEquals("Thu, 5 Nov", labels.dayHeader(today.plusDays(3), today))
    }
}
