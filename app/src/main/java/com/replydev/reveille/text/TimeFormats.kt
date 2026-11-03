package com.replydev.reveille.text

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
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
class TimeFormats internal constructor(
    val locale: Locale,
    val is24Hour: Boolean,
    private val patternFor: (Locale, String) -> String,
) {
    constructor(locale: Locale, is24Hour: Boolean) : this(locale, is24Hour, { l, s -> DateFormat.getBestDateTimePattern(l, s) })

    /**
     * The locale's best pattern for [skeleton], or [fallback] if `java.time` rejects it: ICU may return letters older
     * runtimes don't know (e.g. day period `B`), and a formatter must never throw on the alarm path.
     */
    private fun skeleton(skeleton: String, fallback: String): DateTimeFormatter = try {
        DateTimeFormatter.ofPattern(patternFor(locale, skeleton), locale)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Unusable pattern for $skeleton in $locale; using $fallback", e)
        DateTimeFormatter.ofPattern(fallback, locale)
    }

    // Lazy: each formatter is built (and can fall back) on its own, so time() never depends on a date pattern.
    private val timeFormat by lazy { if (is24Hour) DateTimeFormatter.ofPattern("HH:mm", locale) else skeleton("hmm", "h:mm a") }
    private val digitsFormat by lazy { if (is24Hour) timeFormat else DateTimeFormatter.ofPattern("h:mm", locale) }
    private val periodFormat by lazy { DateTimeFormatter.ofPattern("a", locale) }
    private val headerFormat by lazy { skeleton("EEEEdMMMM", "EEEE d MMMM") }
    private val shortDateFormat by lazy { skeleton("EEEdMMM", "EEE d MMM") }
    private val shortDateYearFormat by lazy { skeleton("EEEdMMMyyyy", "EEE d MMM yyyy") }
    private val dayMonthFormat by lazy { skeleton("dMMM", "d MMM") }
    private val fullDateFormat by lazy { skeleton("dMMMyyyy", "d MMM yyyy") }
    private val sizeFormat by lazy {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
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

    /**
     * Decimal megabytes with one decimal, as Android's own storage screens count them: "6.4", "6,4". The unit is a
     * resource (`update_size_mb`), since some languages abbreviate it differently ("Mo").
     */
    fun megabytes(bytes: Long): String = sizeFormat.format(bytes / 1_000_000.0)

    companion object {
        private const val TAG = "Reveille"

        fun of(context: Context): TimeFormats =
            TimeFormats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
    }
}
