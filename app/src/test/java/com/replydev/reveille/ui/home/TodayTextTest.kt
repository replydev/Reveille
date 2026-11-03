package com.replydev.reveille.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.Source
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import com.replydev.reveille.ui.components.CHECKS_FAILED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TodayTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 3)

    @Test
    fun `issues label counts failing checks`() {
        assertEquals("1 issue", issuesLabel(res, 1))
        assertEquals("3 issues", issuesLabel(res, 3))
        assertEquals("Check failed", issuesLabel(res, CHECKS_FAILED))
    }

    @Test
    fun `backup is two minutes after the armed time`() {
        val at = Instant.parse("2026-11-03T06:55:00Z") // 07:55 in Rome
        assertEquals("backup 07:57", backupText(labels, at, zone))
    }

    @Test
    fun `backup wraps past midnight`() {
        assertEquals("backup 00:01", backupText(labels, Instant.parse("2026-11-03T22:59:00Z"), zone))
    }

    @Test
    fun `alarm count is singular for one`() {
        assertEquals("1 alarm", alarmCount(res, 1))
        assertEquals("9 alarms", alarmCount(res, 9))
        assertEquals("0 alarms", alarmCount(res, 0))
    }

    @Test
    fun `note marks skipped, one-off and multi-week alarms`() {
        val weekly = Source.PolicyRule(1, 10, day)
        val biweekly = Source.PolicyRule(1, 11, day)
        val interval = mapOf(10L to 1, 11L to 2)::get
        assertEquals("skipped", occurrenceNote(res, listOf(biweekly), skipped = true, interval))
        assertEquals("one-off", occurrenceNote(res, listOf(Source.OneOffAlarm(4)), skipped = false, interval))
        assertEquals("every 2 wk", occurrenceNote(res, listOf(biweekly, Source.OneOffAlarm(4)), skipped = false, interval))
        assertNull(occurrenceNote(res, listOf(weekly), skipped = false, interval))
        assertNull(occurrenceNote(res, listOf(Source.PolicyRule(1, 99, day)), skipped = false, interval))
    }

    @Test
    fun `hero opens the first policy, else edits the one-off`() {
        assertEquals(HeroTarget.Policy(2), heroTarget(listOf("O:5", "P:2:7:2026-11-03")))
        assertEquals(HeroTarget.OneOff(5), heroTarget(listOf("O:5")))
        assertNull(heroTarget(emptyList()))
    }
}
