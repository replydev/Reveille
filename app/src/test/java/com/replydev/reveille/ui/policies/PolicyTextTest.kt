package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PolicyTextTest {
    private val today = LocalDate.parse("2026-11-02") // a Monday
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(today, LocalTime.of(8, 0), zone).toInstant()
    private val work = PolicyEntity(id = 1, name = "Work")
    private val rule = RuleEntity(id = 10, policyId = 1, time = LocalTime.of(7, 55), days = setOf(MONDAY, TUESDAY, WEDNESDAY), anchorDate = today)

    @Test
    fun `status line falls back to the next alarm when the policy is active`() {
        assertEquals("Active · next Tue 07:55", statusLine(null, "Tue 07:55"))
        assertEquals("Active", statusLine(null, null))
        assertEquals("Off", statusLine("Off", "Tue 07:55"))
    }

    @Test
    fun `only pause statuses are highlighted`() {
        assertTrue(isPauseStatus("Paused, resumes 14 Nov"))
        assertTrue(isPauseStatus("Paused until it ends on 30 Jun"))
        assertFalse(isPauseStatus("Off"))
        assertFalse(isPauseStatus(null))
    }

    @Test
    fun `rule note shows the interval and the label`() {
        assertNull(ruleNote(rule))
        assertEquals("every 2 wk", ruleNote(rule.copy(intervalWeeks = 2)))
        assertEquals("Early", ruleNote(rule.copy(label = "Early")))
        assertEquals("every 3 wk · Early", ruleNote(rule.copy(intervalWeeks = 3, label = "Early")))
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
        assertEquals("Tomorrow, 07:55", nextText(ZonedDateTime.of(today.plusDays(1), LocalTime.of(7, 55), zone), today))
        assertEquals("Thu 5 Nov, 06:30", nextText(ZonedDateTime.of(today.plusDays(3), LocalTime.of(6, 30), zone), today))
    }

    @Test
    fun `pause dates drop the year only when it is this year`() {
        val pause = PauseEntity(policyId = 1, fromDate = LocalDate.parse("2026-12-23"), toDate = LocalDate.parse("2027-01-06"))
        assertEquals("23 Dec", shortDate(pause.fromDate, today))
        assertEquals("6 Jan 2027", shortDate(pause.toDate, today))
    }
}
