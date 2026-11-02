package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import java.time.LocalTime

class RuleMatchingTest {
    private fun matchingDays(rule: Rule, from: String, days: Long): List<String> {
        val start = LocalDate.parse(from)
        return (0 until days).map { start.plusDays(it) }.filter { ScheduleEngine.ruleMatches(rule, it) }.map { it.toString() }
    }

    @Test
    fun `weekly rule matches its weekdays only`() {
        val rule = Rule(1, LocalTime.of(7, 55), setOf(MONDAY, THURSDAY))
        assertEquals(listOf("2026-11-02", "2026-11-05", "2026-11-09", "2026-11-12"), matchingDays(rule, "2026-11-02", 14))
    }

    @Test
    fun `every two weeks follows the anchor week`() {
        val rule = Rule(1, LocalTime.of(7, 0), setOf(MONDAY), intervalWeeks = 2, anchorDate = LocalDate.parse("2026-11-04"))
        assertEquals(listOf("2026-11-02", "2026-11-16", "2026-11-30"), matchingDays(rule, "2026-11-02", 35))
    }

    @Test
    fun `anchor in the other week shifts the parity`() {
        val rule = Rule(1, LocalTime.of(7, 0), setOf(MONDAY), intervalWeeks = 2, anchorDate = LocalDate.parse("2026-11-09"))
        assertEquals(listOf("2026-11-09", "2026-11-23"), matchingDays(rule, "2026-11-02", 28))
    }

    @Test
    fun `days before the anchor still match`() {
        val rule = Rule(1, LocalTime.of(7, 0), setOf(MONDAY), intervalWeeks = 3, anchorDate = LocalDate.parse("2026-11-23"))
        assertEquals(listOf("2026-11-02", "2026-11-23"), matchingDays(rule, "2026-11-02", 28))
    }

    @Test
    fun `interval below one behaves as weekly`() {
        val rule = Rule(1, LocalTime.of(7, 0), setOf(MONDAY), intervalWeeks = 0)
        assertEquals(listOf("2026-11-02", "2026-11-09"), matchingDays(rule, "2026-11-02", 14))
    }
}
