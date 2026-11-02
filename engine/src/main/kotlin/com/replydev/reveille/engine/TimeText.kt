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

object TimeText {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val fullDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val workWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun time(value: LocalTime): String = value.format(timeFormat)

    fun date(value: LocalDate): String = value.format(fullDateFormat)

    fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(shortDateFormat)
    }

    fun dayAndTime(at: Instant, now: Instant, zone: ZoneId): String {
        val local = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val date = local.toLocalDate()
        val day = when {
            date == today -> "Today"
            date == today.plusDays(1) -> "Tomorrow"
            date.isBefore(today.plusDays(7)) -> date.format(weekdayFormat)
            else -> date.format(shortDateFormat)
        }
        return "$day ${time(local.toLocalTime())}"
    }

    fun relative(at: Instant, now: Instant): String {
        val minutes = Duration.between(now, at).toMinutes()
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "in $minutes min"
            minutes < 48 * 60 -> "in ${(minutes + 30) / 60} h"
            else -> "in ${(minutes + 12 * 60) / (24 * 60)} d"
        }
    }

    fun days(days: Set<DayOfWeek>): String = when {
        days.size == 7 -> "Every day"
        days == workWeek -> "Mon–Fri"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    }
}
