package com.replydev.reveille.ui.oneoff

import com.replydev.reveille.engine.TimeText
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sameYear = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val otherYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

private fun dayDate(date: LocalDate, today: LocalDate): String = date.format(if (date.year == today.year) sameYear else otherYear)

/** The sheet's primary button: "Set for Fri 6 Nov, 09:30". */
fun setForText(date: LocalDate, time: LocalTime, today: LocalDate): String = "Set for ${dayDate(date, today)}, ${TimeText.time(time)}"

/** The label above the mode control: "On Fri 6 Nov" (drawn upper case). */
fun modeOverline(date: LocalDate, today: LocalDate): String = "On ${dayDate(date, today)}"

/** The date strip: today and the next seven days. */
fun quickDates(today: LocalDate): List<LocalDate> = (0L..7L).map { today.plusDays(it) }
