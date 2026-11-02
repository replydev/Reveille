package com.replydev.reveille.engine

import java.time.Instant
import java.time.ZoneId

object Timeline {
    data class Entry(val occurrence: Occurrence, val skipped: Boolean)

    /** Occurrences from [from] through the end of the [days]-th day, including skipped ones (flagged). */
    fun build(snapshot: ScheduleSnapshot, from: Instant, zone: ZoneId, days: Int): List<Entry> {
        val skips = snapshot.skips.toSet()
        return ScheduleEngine
            .upcoming(snapshot.copy(skips = emptyList()), from, zone, limit = Int.MAX_VALUE, horizonDays = days - 1)
            .map { occurrence ->
                val skipped = occurrence.sources.all { it is Source.PolicyRule && Skip(it.policyId, it.date) in skips }
                Entry(occurrence, skipped)
            }
    }
}
