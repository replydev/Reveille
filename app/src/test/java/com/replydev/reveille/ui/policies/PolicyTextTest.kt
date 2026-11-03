package com.replydev.reveille.ui.policies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Skip
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PolicyTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
    private val today = LocalDate.parse("2026-11-02") // a Monday
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(today, LocalTime.of(8, 0), zone).toInstant()
    private val work = PolicyEntity(id = 1, name = "Work")
    private val rule = RuleEntity(id = 10, policyId = 1, time = LocalTime.of(7, 55), days = setOf(MONDAY, TUESDAY, WEDNESDAY), anchorDate = today)

    @Test
    fun `status line falls back to the next alarm when the policy is active`() {
        assertEquals("Active · next Tue 07:55", statusLine(res, null, "Tue 07:55"))
        assertEquals("Active", statusLine(res, null, null))
        assertEquals("Off", statusLine(res, "Off", "Tue 07:55"))
    }

    @Test
    fun `only pause statuses are highlighted`() {
        assertTrue(isPauseStatus(PolicyStatus.Paused(today.plusDays(12))))
        assertTrue(isPauseStatus(PolicyStatus.PausedUntilEnd(today.plusDays(30))))
        assertFalse(isPauseStatus(PolicyStatus.Off))
        assertFalse(isPauseStatus(PolicyStatus.Ended(today.minusDays(1))))
        assertFalse(isPauseStatus(PolicyStatus.Starts(today.plusDays(1))))
        assertFalse(isPauseStatus(null))
    }

    @Test
    fun `rule note shows the interval and the label`() {
        assertNull(ruleNote(res, rule))
        assertEquals("every 2 wk", ruleNote(res, rule.copy(intervalWeeks = 2)))
        assertEquals("Early", ruleNote(res, rule.copy(label = "Early")))
        assertEquals("every 3 wk · Early", ruleNote(res, rule.copy(intervalWeeks = 3, label = "Early")))
    }

    @Test
    fun `next alarm of a policy skips its skipped and replaced days`() {
        val draft = PolicyWithChildren(work, listOf(rule), emptyList()).toModel()
        val snapshot = ScheduleSnapshot(
            oneOffs = listOf(OneOff(5, today.plusDays(1), LocalTime.of(9, 0), mode = OneOffMode.REPLACE_DAY)),
            skips = listOf(Skip(1, today.plusDays(2)), Skip(2, today.plusDays(7))),
        )
        val next = nextPolicyAlarm(draft, snapshot, now, zone)
        assertEquals(ZonedDateTime.of(today.plusDays(7), LocalTime.of(7, 55), zone), next?.at)
    }

    @Test
    fun `no next alarm when nothing rings in the lookahead`() {
        val draft = PolicyWithChildren(work.copy(enabled = false), listOf(rule), emptyList()).toModel()
        assertNull(nextPolicyAlarm(draft, ScheduleSnapshot(), now, zone))
    }

    @Test
    fun `next text names the day and the time`() {
        assertEquals("Tomorrow, 07:55", nextText(labels, ZonedDateTime.of(today.plusDays(1), LocalTime.of(7, 55), zone), today))
        assertEquals("Thu, 5 Nov, 06:30", nextText(labels, ZonedDateTime.of(today.plusDays(3), LocalTime.of(6, 30), zone), today))
    }

    @Test
    fun `pause dates drop the year only when it is this year`() {
        val pause = PauseEntity(policyId = 1, fromDate = LocalDate.parse("2026-12-23"), toDate = LocalDate.parse("2027-01-06"))
        assertEquals("23 Dec", shortDate(labels, pause.fromDate, today))
        assertEquals("6 Jan 2027", shortDate(labels, pause.toDate, today))
    }
}
