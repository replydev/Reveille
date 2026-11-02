package com.replydev.reveille.data

import androidx.room.TypeConverter
import com.replydev.reveille.engine.OneOffMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter fun dateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun timeToMinutes(value: LocalTime): Int = value.hour * 60 + value.minute
    @TypeConverter fun minutesToTime(value: Int): LocalTime = LocalTime.of(value / 60, value % 60)
    @TypeConverter fun daysToMask(value: Set<DayOfWeek>): Int = value.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
    @TypeConverter fun maskToDays(value: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { (value and (1 shl (it.value - 1))) != 0 }.toSet()
    @TypeConverter fun modeToString(value: OneOffMode): String = value.name
    @TypeConverter fun stringToMode(value: String): OneOffMode = OneOffMode.valueOf(value)
    @TypeConverter fun outcomeToString(value: FireOutcome): String = value.name
    @TypeConverter fun stringToOutcome(value: String): FireOutcome = FireOutcome.valueOf(value)
}
