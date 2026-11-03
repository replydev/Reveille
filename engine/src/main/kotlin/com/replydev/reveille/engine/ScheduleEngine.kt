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

    /**
     * The first [limit] occurrences strictly after [from], in instant order, looking at most [horizonDays] days
     * past [from]'s date. Ordering and merging are global, so an alarm pushed past midnight by a DST gap is placed
     * (and merged) correctly among the next day's alarms.
     */
    fun upcoming(
        snapshot: ScheduleSnapshot,
        from: Instant,
        zone: ZoneId,
        limit: Int,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
    ): List<Occurrence> {
        if (limit <= 0) return emptyList()
        val firstDay = from.atZone(zone).toLocalDate()
        val collected = mutableListOf<ResolvedAlarm>()
        val instants = mutableSetOf<Instant>()
        var lastDay: Long? = null
        // Start one day early: a DST gap can push the previous day's late alarm past midnight.
        for (offset in -1L..horizonDays.toLong()) {
            resolvedOn(snapshot, firstDay.plusDays(offset), zone)
                .filter { it.instant.isAfter(from) }
                .forEach { collected += it; instants += it.instant }
            if (offset == lastDay) break
            // A day's alarms resolve no later than the following day, so one more day settles the first [limit].
            if (lastDay == null && instants.size >= limit) lastDay = offset + 1
        }
        return merge(collected).take(limit)
    }

    /** Occurrences in the closed range [[fromInclusive], [toInclusive]], in instant order. */
    fun between(snapshot: ScheduleSnapshot, fromInclusive: Instant, toInclusive: Instant, zone: ZoneId): List<Occurrence> {
        if (toInclusive.isBefore(fromInclusive)) return emptyList()
        val firstDay = fromInclusive.atZone(zone).toLocalDate().minusDays(1)
        val lastDay = toInclusive.atZone(zone).toLocalDate()
        return merge(
            generateSequence(firstDay) { it.plusDays(1) }
                .takeWhile { !it.isAfter(lastDay) }
                .flatMap { resolvedOn(snapshot, it, zone) }
                .filter { !it.instant.isBefore(fromInclusive) && !it.instant.isAfter(toInclusive) }
                .toList(),
        )
    }

    /** True if a REPLACE_DAY one-off on [day] silences every policy that day. */
    fun isDayReplaced(snapshot: ScheduleSnapshot, day: LocalDate): Boolean =
        snapshot.oneOffs.any { it.date == day && it.mode == OneOffMode.REPLACE_DAY }

    /** Resolves [time] on [day]: a gap shifts forward, an overlap takes the earlier offset. */
    fun resolve(day: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime = ZonedDateTime.of(day, time, zone)

    private class ResolvedAlarm(val at: ZonedDateTime, val alarm: DayAlarm) {
        val instant: Instant = at.toInstant()
    }

    /** Policy alarms first, then one-offs, so an occurrence's first source is a policy alarm when there is one. */
    private fun resolvedOn(snapshot: ScheduleSnapshot, day: LocalDate, zone: ZoneId): List<ResolvedAlarm> {
        val policyAlarms = if (isDayReplaced(snapshot, day)) emptyList() else policyAlarmsOn(snapshot, day)
        val oneOffAlarms = snapshot.oneOffs
            .filter { it.date == day }
            .map { DayAlarm(it.time, it.label ?: snapshot.defaultOneOffLabel, Source.OneOffAlarm(it.id)) }
        return (policyAlarms + oneOffAlarms).map { ResolvedAlarm(resolve(day, it.time, zone), it) }
    }

    /** Groups alarms by instant (keeping their relative order), sorted by instant. */
    private fun merge(alarms: List<ResolvedAlarm>): List<Occurrence> =
        alarms
            .groupBy { it.instant }
            .entries
            .sortedBy { it.key }
            .map { (_, group) ->
                Occurrence(
                    at = group.first().at,
                    label = group.map { it.alarm.label }.distinct().joinToString(" · "),
                    sources = group.map { it.alarm.source },
                )
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
