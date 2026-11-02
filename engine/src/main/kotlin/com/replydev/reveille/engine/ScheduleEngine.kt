package com.replydev.reveille.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object ScheduleEngine {
    fun ruleMatches(rule: Rule, day: LocalDate): Boolean {
        if (day.dayOfWeek !in rule.days) return false
        val interval = rule.intervalWeeks.coerceAtLeast(1)
        if (interval == 1) return true
        val weeks = ChronoUnit.WEEKS.between(mondayOf(rule.anchorDate), mondayOf(day))
        return Math.floorMod(weeks, interval.toLong()) == 0L
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
