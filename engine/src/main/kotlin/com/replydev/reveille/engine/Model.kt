package com.replydev.reveille.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A recurring alarm time inside a policy. [anchorDate] picks which weeks count when [intervalWeeks] > 1. */
data class Rule(
    val id: Long,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val intervalWeeks: Int = 1,
    val anchorDate: LocalDate = LocalDate.of(2026, 1, 5),
    val label: String? = null,
)

/** Inclusive date range during which a policy does not ring. */
data class Pause(val from: LocalDate, val to: LocalDate) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

data class Policy(
    val id: Long,
    val name: String,
    val enabled: Boolean = true,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val rules: List<Rule> = emptyList(),
    val pauses: List<Pause> = emptyList(),
)

enum class OneOffMode { REPLACE_DAY, ADDITIVE }

data class OneOff(
    val id: Long,
    val date: LocalDate,
    val time: LocalTime,
    val label: String? = null,
    val mode: OneOffMode = OneOffMode.REPLACE_DAY,
)

data class Skip(val policyId: Long, val date: LocalDate)

data class ScheduleSnapshot(
    val policies: List<Policy> = emptyList(),
    val oneOffs: List<OneOff> = emptyList(),
    val skips: List<Skip> = emptyList(),
)

/** What produced an occurrence. [key] is stable and stored in skips, logs and armed state. */
sealed interface Source {
    val key: String

    data class PolicyRule(val policyId: Long, val ruleId: Long, val date: LocalDate) : Source {
        override val key: String get() = "P:$policyId:$ruleId:$date"
    }

    data class OneOffAlarm(val oneOffId: Long) : Source {
        override val key: String get() = "O:$oneOffId"
    }

    companion object {
        fun parse(key: String): Source? {
            val parts = key.split(":")
            return when {
                parts.size == 4 && parts[0] == "P" -> {
                    val policyId = parts[1].toLongOrNull() ?: return null
                    val ruleId = parts[2].toLongOrNull() ?: return null
                    val date = runCatching { LocalDate.parse(parts[3]) }.getOrNull() ?: return null
                    PolicyRule(policyId, ruleId, date)
                }
                parts.size == 2 && parts[0] == "O" -> parts[1].toLongOrNull()?.let(::OneOffAlarm)
                else -> null
            }
        }
    }
}

/** One moment at which the phone should ring. Alarms at the same instant are merged into one occurrence. */
data class Occurrence(val at: ZonedDateTime, val label: String, val sources: List<Source>) {
    /** The first source's key. Policy alarms are listed before one-offs, so a merged occurrence is keyed by its policy alarm. */
    val key: String get() = sources.first().key
}

/** The alarm currently registered with AlarmManager (or about to be). */
data class Armed(
    val key: String,
    val at: Instant,
    val label: String,
    val sourceKeys: List<String> = emptyList(),
) {
    val isSnooze: Boolean get() = key.startsWith(SNOOZE_PREFIX)

    fun encode(): String =
        listOf(key, at.toEpochMilli().toString(), label.replace(SEPARATOR, " "), sourceKeys.joinToString(","))
            .joinToString(SEPARATOR)

    companion object {
        const val SNOOZE_PREFIX = "S:"
        private const val SEPARATOR = "\u001F"

        /** [at] is truncated to milliseconds so the snooze survives [encode]/[decode] unchanged. */
        fun snooze(at: Instant, label: String): Armed {
            val millis = at.truncatedTo(ChronoUnit.MILLIS)
            return Armed("$SNOOZE_PREFIX${millis.toEpochMilli()}", millis, label)
        }

        fun decode(value: String): Armed? {
            val parts = value.split(SEPARATOR)
            if (parts.size != 4) return null
            val millis = parts[1].toLongOrNull() ?: return null
            return Armed(parts[0], Instant.ofEpochMilli(millis), parts[2], parts[3].split(",").filter { it.isNotEmpty() })
        }
    }
}

fun Occurrence.toArmed(): Armed = Armed(key, at.toInstant(), label, sources.map { it.key })
