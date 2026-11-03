package com.replydev.reveille.ui.policies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.text.Labels
import com.replydev.reveille.text.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PolicyStatusTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = Labels(res, TimeFormats(Locale.UK, is24Hour = true))
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
        assertEquals(PolicyStatus.Off, policyStatus(work.copy(enabled = false, endDate = today.minusDays(1)), emptyList(), today))
    }

    @Test
    fun endedAndNotStartedYet() {
        val ended = LocalDate.parse("2026-10-03")
        val starts = LocalDate.parse("2026-11-10")
        assertEquals(PolicyStatus.Ended(ended), policyStatus(work.copy(endDate = ended), emptyList(), today))
        assertEquals(PolicyStatus.Starts(starts), policyStatus(work.copy(startDate = starts), emptyList(), today))
    }

    @Test
    fun pausedReportsTheDayItResumesAcrossBackToBackPauses() {
        val pauses = listOf(pause("2026-11-01", "2026-11-05"), pause("2026-11-06", "2027-01-05"))
        assertEquals(PolicyStatus.Paused(LocalDate.parse("2027-01-06")), policyStatus(work, pauses, today))
        assertNull(policyStatus(work, listOf(pause("2026-11-03", "2026-11-05")), today))
    }

    @Test
    fun pauseRunningPastTheEndDateReadsAsPausedUntilItEnds() {
        val end = LocalDate.parse("2026-11-04")
        val status = policyStatus(work.copy(endDate = end), listOf(pause("2026-11-01", "2026-11-30")), today)
        assertEquals(PolicyStatus.PausedUntilEnd(end), status)
    }

    @Test
    fun statusesRenderAsText() {
        assertEquals("Off", PolicyStatus.Off.text(labels, today))
        assertEquals("Ended 3 Oct", PolicyStatus.Ended(LocalDate.parse("2026-10-03")).text(labels, today))
        assertEquals("Ended 3 Oct 2025", PolicyStatus.Ended(LocalDate.parse("2025-10-03")).text(labels, today))
        assertEquals("Starts 10 Nov", PolicyStatus.Starts(LocalDate.parse("2026-11-10")).text(labels, today))
        assertEquals("Paused, resumes 6 Jan 2027", PolicyStatus.Paused(LocalDate.parse("2027-01-06")).text(labels, today))
        assertEquals("Paused until it ends on 4 Nov", PolicyStatus.PausedUntilEnd(LocalDate.parse("2026-11-04")).text(labels, today))
    }

    @Test
    fun warnsWhenTheDraftHasNoAlarmInTheNextTwoWeeks() {
        assertNull(upcomingWarning(res, work, listOf(monday), emptyList(), now, zone))
        assertEquals(
            "This policy has no alarms in the next 14 days.",
            upcomingWarning(res, work, listOf(monday), listOf(pause("2026-11-01", "2026-11-30")), now, zone),
        )
        assertEquals(
            "This policy is off: none of its alarms will ring.",
            upcomingWarning(res, work.copy(enabled = false), listOf(monday), emptyList(), now, zone),
        )
        assertNull(upcomingWarning(res, work, emptyList(), emptyList(), now, zone))
    }
}
