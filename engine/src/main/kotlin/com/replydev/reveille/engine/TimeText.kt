package com.replydev.reveille.engine

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val fullDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
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

    // English string API, kept until the app renders the values above (removed in the i18n cleanup).

    fun time(value: LocalTime): String = value.format(timeFormat)

    fun date(value: LocalDate): String = value.format(fullDateFormat)

    fun dayHeaderText(date: LocalDate, today: LocalDate): String = englishDay(dayHeader(date, today))

    fun dayAndTime(at: Instant, now: Instant, zone: ZoneId): String =
        "${englishDay(dayOf(at, now, zone))} ${time(at.atZone(zone).toLocalTime())}"

    fun relative(at: Instant, now: Instant): String = when (val r = relativeOf(at, now)) {
        Relative.Now -> "now"
        is Relative.Minutes -> "in ${r.count} min"
        is Relative.Hours -> "in ${r.count} h"
        is Relative.Days -> "in ${r.count} d"
    }

    fun days(days: Set<DayOfWeek>): String = when (val set = daySetOf(days)) {
        DaySet.EveryDay -> "Every day"
        DaySet.Weekdays -> "Mon–Fri"
        is DaySet.Custom -> set.days.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    }

    private fun englishDay(label: DayLabel): String = when (label) {
        DayLabel.Today -> "Today"
        DayLabel.Tomorrow -> "Tomorrow"
        is DayLabel.Weekday -> label.date.format(weekdayFormat)
        is DayLabel.Date -> label.date.format(shortDateFormat)
    }
}
