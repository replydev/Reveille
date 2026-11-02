package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.time.LocalTime

class PolicyValidationTest {
    private val day = LocalDate.parse("2026-11-02")
    private val rule = RuleEntity(policyId = 0, time = LocalTime.of(7, 55), days = setOf(MONDAY), anchorDate = day)

    @Test
    fun validPolicyHasNoErrors() {
        assertTrue(validatePolicy("Work", null, null, listOf(rule), emptyList()).isEmpty())
    }

    @Test
    fun reportsEveryProblem() {
        val errors = validatePolicy(
            name = " ",
            startDate = day,
            endDate = day.minusDays(1),
            rules = listOf(rule.copy(days = emptySet())),
            pauses = listOf(PauseEntity(policyId = 0, fromDate = day, toDate = day.minusDays(1))),
        )
        assertEquals(
            listOf(
                "Give the policy a name.",
                "Every alarm time needs at least one weekday.",
                "The end date is before the start date.",
                "A pause ends before it starts.",
            ),
            errors,
        )
    }

    @Test
    fun requiresAtLeastOneRule() {
        assertEquals(listOf("Add at least one alarm time."), validatePolicy("Work", null, null, emptyList(), emptyList()))
    }
}
