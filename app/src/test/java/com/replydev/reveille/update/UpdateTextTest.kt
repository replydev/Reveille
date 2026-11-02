package com.replydev.reveille.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UpdateTextTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T15:00:00Z") // 16:00 in Rome

    @Test
    fun lastChecked() {
        assertEquals("Never", UpdateText.lastChecked(null, now, rome))
        assertEquals("Today, 09:12", UpdateText.lastChecked(Instant.parse("2026-11-03T08:12:00Z"), now, rome))
        assertEquals("Yesterday, 23:50", UpdateText.lastChecked(Instant.parse("2026-11-02T22:50:00Z"), now, rome))
        assertEquals("30 Oct, 07:05", UpdateText.lastChecked(Instant.parse("2026-10-30T06:05:00Z"), now, rome))
    }

    @Test
    fun releasedAndSize() {
        assertEquals("2 Nov", UpdateText.released(Instant.parse("2026-11-02T22:41:36Z"), rome))
        assertEquals("30.4 MB", UpdateText.size(30_370_722))
        assertEquals("6.1 MB", UpdateText.size(6_100_000))
        assertEquals("0.4 MB", UpdateText.size(400_000))
    }

    @Test
    fun theReleaseSheetSubtitle() {
        val release = TestReleases.release("v0.3.0")
        assertEquals("From v0.2.0 · released 2\u00A0Nov · 30.4\u00A0MB", UpdateText.sheetSubtitle(release, "0.2.0", rome))
    }

    @Test
    fun ringsInRoundsUpAndNeverSaysZero() {
        val at = Instant.parse("2026-11-04T06:55:00Z")
        assertEquals("Work rings in 18 min", UpdateText.ringsIn("Work", at, at.minusSeconds(18 * 60)))
        assertEquals("Work rings in 18 min", UpdateText.ringsIn("Work", at, at.minusSeconds(17 * 60 + 1)))
        assertEquals("Work rings in 1 min", UpdateText.ringsIn("Work", at, at.minusSeconds(5)))
        assertEquals("Work is due now", UpdateText.ringsIn("Work", at, at.plusSeconds(30)))
    }

    @Test
    fun remindAfterIsFiveMinutesPastTheAlarm() {
        val at = Instant.parse("2026-11-04T06:55:00Z")
        assertEquals(Instant.parse("2026-11-04T07:00:00Z"), UpdateText.remindAfter(at))
        assertEquals("07:55", UpdateText.time(at, rome))
    }
}
