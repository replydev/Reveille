package com.replydev.reveille.engine

import java.time.Instant
import java.time.ZoneId

object Timeline {
    data class Entry(val occurrence: Occurrence, val skipped: Boolean)

    /**
     * Occurrences from [from] through the end of the [days]-th day. Real occurrences come from the actual schedule;
     * each skipped policy alarm that would otherwise ring gets its own entry flagged [Entry.skipped], so it never
     * merges with a real alarm at the same instant. At equal instants real entries come first.
     */
    fun build(snapshot: ScheduleSnapshot, from: Instant, zone: ZoneId, days: Int): List<Entry> {
        if (days <= 0) return emptyList()
        val real = ScheduleEngine
            .upcoming(snapshot, from, zone, limit = Int.MAX_VALUE, horizonDays = days - 1)
            .map { Entry(it, skipped = false) }
        val unskipped = snapshot.copy(skips = emptyList())
        val firstDay = from.atZone(zone).toLocalDate()
        val skipped = (0L until days.toLong()).flatMap { offset ->
            val day = firstDay.plusDays(offset)
            val skippedPolicies = snapshot.skips.filter { it.date == day }.map { it.policyId }.toSet()
            if (skippedPolicies.isEmpty() || ScheduleEngine.isDayReplaced(snapshot, day)) return@flatMap emptyList()
            ScheduleEngine.policyAlarmsOn(unskipped, day)
                .filter { (it.source as Source.PolicyRule).policyId in skippedPolicies }
                .map { Occurrence(ScheduleEngine.resolve(day, it.time, zone), it.label, listOf(it.source)) }
                .filter { it.at.toInstant().isAfter(from) }
                .map { Entry(it, skipped = true) }
        }
        return (real + skipped).sortedWith(compareBy<Entry>({ it.occurrence.at.toInstant() }, { it.skipped }))
    }
}
