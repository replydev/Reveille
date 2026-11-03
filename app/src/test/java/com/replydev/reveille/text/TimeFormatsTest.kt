package com.replydev.reveille.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimeFormatsTest {
    private val date = LocalDate.of(2026, 11, 2) // a Monday
    private val en24 = TimeFormats(Locale.UK, is24Hour = true)
    private val en12 = TimeFormats(Locale.US, is24Hour = false)
    private val it24 = TimeFormats(Locale.ITALIAN, is24Hour = true)
    private val de24 = TimeFormats(Locale.GERMAN, is24Hour = true)

    @Test
    fun `24 hour times are zero padded`() {
        assertEquals("07:05", en24.time(LocalTime.of(7, 5)))
        assertEquals("19:30", it24.time(LocalTime.of(19, 30)))
    }

    @Test
    fun `12 hour times carry a period`() {
        assertEquals("7:05 AM".normalizeSpaces(), en12.time(LocalTime.of(7, 5)).normalizeSpaces())
        val parts = en12.timeParts(LocalTime.of(19, 30))
        assertEquals("7:30", parts.digits)
        assertEquals("PM", parts.period)
        assertNull(en24.timeParts(LocalTime.of(19, 30)).period)
        assertEquals("19:30", en24.timeParts(LocalTime.of(19, 30)).digits)
    }

    @Test
    fun `dates follow the locale's order and names`() {
        assertEquals("lunedì 2 novembre", it24.headerDate(date).lowercase())
        assertEquals("Montag, 2. November", de24.headerDate(date))
        assertEquals("Monday 2 November", en24.headerDate(date).replace(",", ""))
        assertEquals("2 nov 2026", it24.fullDate(date))
    }

    @Test
    fun `weekday names are localized`() {
        assertEquals("lun", it24.weekdayShort(DayOfWeek.MONDAY).lowercase().trimEnd('.'))
        assertEquals("Mittwoch", de24.weekdayFull(DayOfWeek.WEDNESDAY))
        assertEquals("M", en24.weekdayNarrow(DayOfWeek.MONDAY))
    }

    @Test
    fun `sizes use the locale's decimal separator`() {
        assertEquals("6.4", en24.megabytes(6_400_000))
        assertEquals("6,4", it24.megabytes(6_400_000))
        assertEquals("30.4", en24.megabytes(30_370_722))
    }

    @Test
    fun `every shipped locale formats without throwing`() {
        for (tag in listOf("en", "it", "es", "fr", "de")) {
            for (h24 in listOf(true, false)) {
                val f = TimeFormats(Locale.forLanguageTag(tag), h24)
                f.time(LocalTime.NOON); f.timeParts(LocalTime.NOON)
                f.headerDate(date); f.shortDate(date); f.shortDate(date, 2025); f.dayMonth(date); f.fullDate(date)
                DayOfWeek.entries.forEach { f.weekdayShort(it); f.weekdayNarrow(it); f.weekdayFull(it) }
                f.megabytes(1_234_567)
            }
        }
    }

    @Test
    fun `a skeleton pattern java time rejects falls back instead of throwing`() {
        val bad = "bbbb {"
        assertThrows(IllegalArgumentException::class.java) { DateTimeFormatter.ofPattern(bad, Locale.US) }
        for (h24 in listOf(true, false)) {
            val f = TimeFormats(Locale.US, h24) { _, _ -> bad }
            assertEquals(if (h24) "07:05" else "7:05 AM", f.time(LocalTime.of(7, 5)).normalizeSpaces())
            assertTrue(f.timeParts(LocalTime.of(7, 5)).digits.isNotEmpty())
            assertEquals("Monday 2 November", f.headerDate(date))
            assertEquals("Mon 2 Nov", f.shortDate(date))
            assertEquals("Mon 2 Nov 2026", f.shortDate(date, 2025))
            assertEquals("2 Nov", f.dayMonth(date))
            assertEquals("2 Nov 2026", f.fullDate(date))
        }
    }

    private fun String.normalizeSpaces() = replace('\u202F', ' ').replace('\u00A0', ' ')
}
