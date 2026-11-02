package com.replydev.reveille.ui.oneoff

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OneOffTextTest {
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun `set for names the date and the time`() {
        assertEquals("Set for Fri 6 Nov, 09:30", setForText(LocalDate.parse("2026-11-06"), LocalTime.of(9, 30), today))
        assertEquals("Set for Tue 3 Nov, 07:00", setForText(today.plusDays(1), LocalTime.of(7, 0), today))
    }

    @Test
    fun `set for adds the year outside the current one`() {
        assertEquals("Set for Wed 6 Jan 2027, 06:05", setForText(LocalDate.parse("2027-01-06"), LocalTime.of(6, 5), today))
    }

    @Test
    fun `mode overline names the day`() {
        assertEquals("On Fri 6 Nov", modeOverline(LocalDate.parse("2026-11-06"), today))
        assertEquals("On Wed 6 Jan 2027", modeOverline(LocalDate.parse("2027-01-06"), today))
    }

    @Test
    fun `quick dates are today and the next seven days`() {
        val dates = quickDates(today)
        assertEquals(8, dates.size)
        assertEquals(today, dates.first())
        assertEquals(LocalDate.parse("2026-11-09"), dates.last())
    }
}
