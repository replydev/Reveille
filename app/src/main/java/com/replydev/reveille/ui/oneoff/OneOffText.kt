package com.replydev.reveille.ui.oneoff

import com.replydev.reveille.R
import com.replydev.reveille.text.Labels
import java.time.LocalDate
import java.time.LocalTime

/** The sheet's primary button: "Set for Fri, 6 Nov, 09:30". */
fun setForText(labels: Labels, date: LocalDate, time: LocalTime, today: LocalDate): String =
    labels.res.getString(R.string.oneoff_set_for, labels.formats.shortDate(date, today.year), labels.formats.time(time))

/** The label above the mode control: "On Fri, 6 Nov" (drawn upper case). */
fun modeOverline(labels: Labels, date: LocalDate, today: LocalDate): String =
    labels.res.getString(R.string.oneoff_on_date, labels.formats.shortDate(date, today.year))

/** The date strip: today and the next seven days. */
fun quickDates(today: LocalDate): List<LocalDate> = (0L..7L).map { today.plusDays(it) }
