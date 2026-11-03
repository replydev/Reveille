package com.replydev.reveille.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UpdateTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val text = UpdateText(res, Labels(res, TimeFormats(Locale.UK, is24Hour = true)))
    private val rome = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T15:00:00Z") // 16:00 in Rome

    @Test
    fun lastChecked() {
        assertEquals("Never", text.lastChecked(null, now, rome))
        assertEquals("Today, 09:12", text.lastChecked(Instant.parse("2026-11-03T08:12:00Z"), now, rome))
        assertEquals("Yesterday, 23:50", text.lastChecked(Instant.parse("2026-11-02T22:50:00Z"), now, rome))
        assertEquals("30 Oct, 07:05", text.lastChecked(Instant.parse("2026-10-30T06:05:00Z"), now, rome))
    }

    @Test
    fun releasedAndSize() {
        assertEquals("2 Nov", text.released(Instant.parse("2026-11-02T22:41:36Z"), rome))
        assertEquals("30.4 MB", text.size(30_370_722))
        assertEquals("6.1 MB", text.size(6_100_000))
        assertEquals("0.4 MB", text.size(400_000))
    }

    @Test
    fun theReleaseSheetSubtitle() {
        val release = TestReleases.release("v0.3.0")
        assertEquals("From v0.2.0 · released 2\u00A0Nov · 30.4\u00A0MB", text.sheetSubtitle(release, "0.2.0", rome))
    }

    @Test
    fun ringsInRoundsUpAndNeverSaysZero() {
        val at = Instant.parse("2026-11-04T06:55:00Z")
        assertEquals("Work rings in 18 min", text.ringsIn("Work", at, at.minusSeconds(18 * 60)))
        assertEquals("Work rings in 18 min", text.ringsIn("Work", at, at.minusSeconds(17 * 60 + 1)))
        assertEquals("Work rings in 1 min", text.ringsIn("Work", at, at.minusSeconds(5)))
        assertEquals("Work is due now", text.ringsIn("Work", at, at.plusSeconds(30)))
    }

    @Test
    fun remindAfterIsFiveMinutesPastTheAlarm() {
        val at = Instant.parse("2026-11-04T06:55:00Z")
        assertEquals(Instant.parse("2026-11-04T07:00:00Z"), reminderAt(at))
        assertEquals("07:55", text.time(at, rome))
    }
}
