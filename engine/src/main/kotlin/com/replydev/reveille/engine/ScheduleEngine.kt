package com.replydev.reveille.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** An alarm on a specific day, before it is resolved to an instant. */
data class DayAlarm(val time: LocalTime, val label: String, val source: Source)

object ScheduleEngine {
    const val DEFAULT_HORIZON_DAYS = 400
    const val DEFAULT_ONE_OFF_LABEL = "Alarm"

    /** Occurrences strictly after [from], in order, looking at most [horizonDays] days past [from]'s date. */
    fun upcoming(
        snapshot: ScheduleSnapshot,
        from: Instant,
        zone: ZoneId,
        limit: Int,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
    ): List<Occurrence> {
        if (limit <= 0) return emptyList()
        val firstDay = from.atZone(zone).toLocalDate()
        val result = mutableListOf<Occurrence>()
        for (offset in 0..horizonDays) {
            occurrencesOn(snapshot, firstDay.plusDays(offset.toLong()), zone)
                .filterTo(result) { it.at.toInstant().isAfter(from) }
            if (result.size >= limit) break
        }
        return result.take(limit)
    }

    fun occurrencesOn(snapshot: ScheduleSnapshot, day: LocalDate, zone: ZoneId): List<Occurrence> {
        val oneOffs = snapshot.oneOffs.filter { it.date == day }
        val replacesDay = oneOffs.any { it.mode == OneOffMode.REPLACE_DAY }
        val policyAlarms = if (replacesDay) emptyList() else policyAlarmsOn(snapshot, day)
        val oneOffAlarms = oneOffs.map { DayAlarm(it.time, it.label ?: DEFAULT_ONE_OFF_LABEL, Source.OneOffAlarm(it.id)) }
        return (policyAlarms + oneOffAlarms)
            .groupBy { ZonedDateTime.of(day, it.time, zone) }
            .entries
            .sortedBy { it.key.toInstant() }
            .map { (at, group) ->
                Occurrence(
                    at = at,
                    label = group.map { it.label }.distinct().joinToString(" · "),
                    sources = group.map { it.source },
                )
            }
    }

    /** Policy alarms on [day], ignoring one-offs. The one-off editor uses this to show what it replaces. */
    fun policyAlarmsOn(snapshot: ScheduleSnapshot, day: LocalDate): List<DayAlarm> =
        snapshot.policies
            .filter { isActiveOn(it, day, snapshot.skips) }
            .flatMap { policy ->
                policy.rules
                    .filter { ruleMatches(it, day) }
                    .map { rule -> DayAlarm(rule.time, rule.label ?: policy.name, Source.PolicyRule(policy.id, rule.id, day)) }
            }
            .sortedBy { it.time }

    fun ruleMatches(rule: Rule, day: LocalDate): Boolean {
        if (day.dayOfWeek !in rule.days) return false
        val interval = rule.intervalWeeks.coerceAtLeast(1)
        if (interval == 1) return true
        val weeks = ChronoUnit.WEEKS.between(mondayOf(rule.anchorDate), mondayOf(day))
        return Math.floorMod(weeks, interval.toLong()) == 0L
    }

    private fun isActiveOn(policy: Policy, day: LocalDate, skips: List<Skip>): Boolean {
        val start = policy.startDate
        val end = policy.endDate
        return policy.enabled &&
            (start == null || !day.isBefore(start)) &&
            (end == null || !day.isAfter(end)) &&
            policy.pauses.none { it.contains(day) } &&
            skips.none { it.policyId == policy.id && it.date == day }
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
