package com.replydev.reveille.text

import android.content.Context
import android.text.format.DateFormat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A time split for large displays: "7:30" and "PM" are drawn in different sizes. [period] is null in 24-hour mode. */
data class TimeParts(val digits: String, val period: String?)

/**
 * Locale-aware formatting for everything Reveille shows about time. Date patterns come from the locale's own
 * skeletons (CLDR), so word order and punctuation are native ("lunedì 2 novembre", "Montag, 2. November").
 */
class TimeFormats(val locale: Locale, val is24Hour: Boolean) {
    private fun skeleton(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val timeFormat = if (is24Hour) DateTimeFormatter.ofPattern("HH:mm", locale) else skeleton("hmm")
    private val digitsFormat = if (is24Hour) timeFormat else DateTimeFormatter.ofPattern("h:mm", locale)
    private val periodFormat = DateTimeFormatter.ofPattern("a", locale)
    private val headerFormat = skeleton("EEEEdMMMM")
    private val shortDateFormat = skeleton("EEEdMMM")
    private val shortDateYearFormat = skeleton("EEEdMMMyyyy")
    private val dayMonthFormat = skeleton("dMMM")
    private val fullDateFormat = skeleton("dMMMyyyy")
    private val sizeFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    fun time(value: LocalTime): String = value.format(timeFormat)

    fun timeParts(value: LocalTime): TimeParts =
        TimeParts(value.format(digitsFormat), if (is24Hour) null else value.format(periodFormat))

    /** "Monday, 2 November": Today header and ringing screen. */
    fun headerDate(value: LocalDate): String = value.format(headerFormat)

    /** "Mon 2 Nov", or with the year when it isn't [currentYear]. */
    fun shortDate(value: LocalDate, currentYear: Int = value.year): String =
        value.format(if (value.year == currentYear) shortDateFormat else shortDateYearFormat)

    /** "2 Nov". */
    fun dayMonth(value: LocalDate): String = value.format(dayMonthFormat)

    /** "2 Nov 2026". */
    fun fullDate(value: LocalDate): String = value.format(fullDateFormat)

    fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)
    fun weekdayNarrow(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, locale)
    fun weekdayFull(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, locale)

    /** Decimal megabytes with one decimal, as Android's own storage screens count them. */
    fun size(bytes: Long): String = "${sizeFormat.format(bytes / 1_000_000.0)} MB"

    companion object {
        fun of(context: Context): TimeFormats =
            TimeFormats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
    }
}
