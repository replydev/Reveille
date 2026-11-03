package com.replydev.reveille.ui.oneoff

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OneOffTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun `set for names the date and the time`() {
        assertEquals("Set for Fri, 6 Nov, 09:30", setForText(labels, LocalDate.parse("2026-11-06"), LocalTime.of(9, 30), today))
        assertEquals("Set for Tue, 3 Nov, 07:00", setForText(labels, today.plusDays(1), LocalTime.of(7, 0), today))
    }

    @Test
    fun `set for adds the year outside the current one`() {
        assertEquals("Set for Wed, 6 Jan 2027, 06:05", setForText(labels, LocalDate.parse("2027-01-06"), LocalTime.of(6, 5), today))
    }

    @Test
    fun `mode overline names the day`() {
        assertEquals("On Fri, 6 Nov", modeOverline(labels, LocalDate.parse("2026-11-06"), today))
        assertEquals("On Wed, 6 Jan 2027", modeOverline(labels, LocalDate.parse("2027-01-06"), today))
    }

    @Test
    fun `quick dates are today and the next seven days`() {
        val dates = quickDates(today)
        assertEquals(8, dates.size)
        assertEquals(today, dates.first())
        assertEquals(LocalDate.parse("2026-11-09"), dates.last())
    }
}
