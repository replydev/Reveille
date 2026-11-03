package com.replydev.reveille.engine

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which day an instant or date falls on, relative to today. The app turns it into words. */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Tomorrow : DayLabel
    /** Within the next 7 days: rendered as a weekday name. */
    data class Weekday(val date: LocalDate) : DayLabel
    data class Date(val date: LocalDate) : DayLabel
}

/** How far ahead something is, rounded for display. */
sealed interface Relative {
    data object Now : Relative
    data class Minutes(val count: Long) : Relative
    data class Hours(val count: Long) : Relative
    data class Days(val count: Long) : Relative
}

/** A rule's weekdays, summarised. [Custom] days are in Monday-first order. */
sealed interface DaySet {
    data object EveryDay : DaySet
    data object Weekdays : DaySet
    data class Custom(val days: List<DayOfWeek>) : DaySet
}

object TimeText {
    private val workWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun dayHeader(date: LocalDate, today: LocalDate): DayLabel = when (date) {
        today -> DayLabel.Today
        today.plusDays(1) -> DayLabel.Tomorrow
        else -> DayLabel.Date(date)
    }

    fun dayOf(at: Instant, now: Instant, zone: ZoneId): DayLabel {
        val today = now.atZone(zone).toLocalDate()
        val date = at.atZone(zone).toLocalDate()
        return when {
            date == today -> DayLabel.Today
            date == today.plusDays(1) -> DayLabel.Tomorrow
            date.isBefore(today.plusDays(7)) -> DayLabel.Weekday(date)
            else -> DayLabel.Date(date)
        }
    }

    fun relativeOf(at: Instant, now: Instant): Relative {
        val minutes = Duration.between(now, at).toMinutes()
        return when {
            minutes < 1 -> Relative.Now
            minutes < 60 -> Relative.Minutes(minutes)
            minutes < 48 * 60 -> Relative.Hours((minutes + 30) / 60)
            else -> Relative.Days((minutes + 12 * 60) / (24 * 60))
        }
    }

    fun daySetOf(days: Set<DayOfWeek>): DaySet = when {
        days.size == 7 -> DaySet.EveryDay
        days == workWeek -> DaySet.Weekdays
        else -> DaySet.Custom(DayOfWeek.entries.filter { it in days })
    }
}
