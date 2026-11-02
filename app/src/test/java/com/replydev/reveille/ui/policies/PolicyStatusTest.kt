package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PolicyStatusTest {
    private val today = LocalDate.parse("2026-11-02")
    private val work = PolicyEntity(id = 1, name = "Work")
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(today, LocalTime.of(6, 0), zone).toInstant()
    private val monday = RuleEntity(policyId = 1, time = LocalTime.of(7, 0), days = setOf(MONDAY), anchorDate = today)

    private fun pause(from: String, to: String) = PauseEntity(policyId = 1, fromDate = LocalDate.parse(from), toDate = LocalDate.parse(to))

    @Test
    fun activePolicyHasNoStatus() {
        assertNull(policyStatus(work, emptyList(), today))
    }

    @Test
    fun disabledPolicyIsOff() {
        assertEquals("Off", policyStatus(work.copy(enabled = false, endDate = today.minusDays(1)), emptyList(), today))
    }

    @Test
    fun endedAndNotStartedYet() {
        assertEquals("Ended 3 Oct", policyStatus(work.copy(endDate = LocalDate.parse("2026-10-03")), emptyList(), today))
        assertEquals("Starts 10 Nov", policyStatus(work.copy(startDate = LocalDate.parse("2026-11-10")), emptyList(), today))
        assertEquals("Ended 3 Oct 2025", policyStatus(work.copy(endDate = LocalDate.parse("2025-10-03")), emptyList(), today))
    }

    @Test
    fun pausedReportsTheDayItResumesAcrossBackToBackPauses() {
        val pauses = listOf(pause("2026-11-01", "2026-11-05"), pause("2026-11-06", "2027-01-05"))
        assertEquals("Paused, resumes 6 Jan 2027", policyStatus(work, pauses, today))
        assertNull(policyStatus(work, listOf(pause("2026-11-03", "2026-11-05")), today))
    }

    @Test
    fun pauseRunningPastTheEndDateReadsAsPausedUntilItEnds() {
        val status = policyStatus(work.copy(endDate = LocalDate.parse("2026-11-04")), listOf(pause("2026-11-01", "2026-11-30")), today)
        assertEquals("Paused until it ends on 4 Nov", status)
    }

    @Test
    fun warnsWhenTheDraftHasNoAlarmInTheNextTwoWeeks() {
        assertNull(upcomingWarning(work, listOf(monday), emptyList(), now, zone))
        assertEquals(
            "This policy has no alarms in the next 14 days.",
            upcomingWarning(work, listOf(monday), listOf(pause("2026-11-01", "2026-11-30")), now, zone),
        )
        assertEquals(
            "This policy is off: none of its alarms will ring.",
            upcomingWarning(work.copy(enabled = false), listOf(monday), emptyList(), now, zone),
        )
        assertNull(upcomingWarning(work, emptyList(), emptyList(), now, zone))
    }
}
