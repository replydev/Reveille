package com.replydev.reveille.ui.reliability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HealthTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun `summary counts passing checks and warns while any fails`() {
        val summary = healthSummary(res, passing = 7, total = 8)
        assertEquals("7 of 8 checks pass", summary.text)
        assertEquals("Alarms may not ring", summary.verdict)
        assertFalse(summary.allGood)
    }

    @Test
    fun `summary is all good when every check passes`() {
        val summary = healthSummary(res, passing = 8, total = 8)
        assertEquals("8 of 8 checks pass", summary.text)
        assertEquals("All good", summary.verdict)
        assertTrue(summary.allGood)
    }

    @Test
    fun `passing note marks HyperOS and confirmed manual checks`() {
        assertEquals("HyperOS", passingNote(res, "miui_autostart", manual = true))
        assertEquals("Confirmed", passingNote(res, "oem_battery", manual = true))
        assertNull(passingNote(res, "exact_alarms", manual = false))
    }

    @Test
    fun `log time names the day and the time`() {
        val at = ZonedDateTime.of(2026, 11, 2, 7, 55, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Today 07:55", logTime(labels, at, today, zone))
        val older = ZonedDateTime.of(2026, 10, 30, 8, 55, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Fri, 30 Oct 08:55", logTime(labels, older, today, zone))
    }

    @Test
    fun `outcome reads as words and fired is unanswered`() {
        assertEquals("rang, not answered yet", outcomeText(res, FireOutcome.FIRED))
        assertEquals("dismissed", outcomeText(res, FireOutcome.DISMISSED))
        assertEquals("snoozed", outcomeText(res, FireOutcome.SNOOZED))
        assertEquals("auto snoozed", outcomeText(res, FireOutcome.AUTO_SNOOZED))
        assertEquals("skipped", outcomeText(res, FireOutcome.SKIPPED))
        assertEquals("superseded", outcomeText(res, FireOutcome.SUPERSEDED))
        assertEquals("missed", outcomeText(res, FireOutcome.MISSED))
    }
}
