package com.replydev.reveille.text

import android.content.Context
import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.engine.DayLabel
import com.replydev.reveille.engine.DaySet
import com.replydev.reveille.engine.Relative
import com.replydev.reveille.engine.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Turns the engine's time values into words in the current language. The only place that does. */
class Labels(private val res: Resources, val formats: TimeFormats) {
    fun day(label: DayLabel): String = when (label) {
        DayLabel.Today -> res.getString(R.string.time_today)
        DayLabel.Tomorrow -> res.getString(R.string.time_tomorrow)
        is DayLabel.Weekday -> formats.weekdayShort(label.date.dayOfWeek)
        is DayLabel.Date -> formats.shortDate(label.date)
    }

    fun relative(value: Relative): String = when (value) {
        Relative.Now -> res.getString(R.string.time_now)
        is Relative.Minutes -> plural(R.plurals.time_in_minutes, value.count)
        is Relative.Hours -> plural(R.plurals.time_in_hours, value.count)
        is Relative.Days -> plural(R.plurals.time_in_days, value.count)
    }

    fun relative(at: Instant, now: Instant): String = relative(TimeText.relativeOf(at, now))

    fun days(set: DaySet): String = when (set) {
        DaySet.EveryDay -> res.getString(R.string.time_every_day)
        DaySet.Weekdays -> res.getString(R.string.time_weekdays)
        is DaySet.Custom -> set.days.joinToString(" ") { formats.weekdayShort(it) }
    }

    /** "Tomorrow 07:55", "Thu 08:55", "Wed, 18 Nov 08:00" (en-GB). */
    fun dayAndTime(at: Instant, now: Instant, zone: ZoneId): String =
        res.getString(R.string.time_day_and_time, dayPart(at, now, zone), formats.time(at.atZone(zone).toLocalTime()))

    /** The day half of [dayAndTime]. */
    fun dayPart(at: Instant, now: Instant, zone: ZoneId): String = day(TimeText.dayOf(at, now, zone))

    fun dayHeader(date: LocalDate, today: LocalDate): String = day(TimeText.dayHeader(date, today))

    fun defaultOneOffLabel(): String = res.getString(R.string.oneoff_default_label)

    private fun plural(id: Int, count: Long): String {
        val n = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return res.getQuantityString(id, n, n)
    }

    companion object {
        fun of(context: Context): Labels = Labels(context.resources, TimeFormats.of(context))
    }
}
