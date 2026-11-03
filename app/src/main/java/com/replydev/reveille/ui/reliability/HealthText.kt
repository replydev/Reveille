package com.replydev.reveille.ui.reliability

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.text.Labels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The line under the Health title: "X of Y checks pass", and the verdict beside it. */
data class HealthSummary(val text: String, val verdict: String, val allGood: Boolean)

fun healthSummary(res: Resources, passing: Int, total: Int): HealthSummary {
    val allGood = passing == total
    return HealthSummary(
        res.getQuantityString(R.plurals.health_summary, total, passing, total),
        res.getString(if (allGood) R.string.health_all_good else R.string.health_may_not_ring),
        allGood,
    )
}

/** The muted note on a passing check: "HyperOS" for the Xiaomi-only checks, "Confirmed" for other manual ones. */
fun passingNote(res: Resources, id: String, manual: Boolean): String? = when {
    id.startsWith("miui_") -> res.getString(R.string.health_note_hyperos)
    manual -> res.getString(R.string.health_note_confirmed)
    else -> null
}

/** "Today 07:55", "Fri, 30 Oct 08:55": when a logged alarm was due. */
fun logTime(labels: Labels, scheduledAt: Long, today: LocalDate, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(scheduledAt).atZone(zone)
    return labels.res.getString(R.string.time_day_and_time, labels.dayHeader(at.toLocalDate(), today), labels.formats.time(at.toLocalTime()))
}

/** FIRED means rung but never dismissed or snoozed. */
fun outcomeText(res: Resources, outcome: FireOutcome): String = res.getString(
    when (outcome) {
        FireOutcome.FIRED -> R.string.health_outcome_fired
        FireOutcome.DISMISSED -> R.string.health_outcome_dismissed
        FireOutcome.SNOOZED -> R.string.health_outcome_snoozed
        FireOutcome.AUTO_SNOOZED -> R.string.health_outcome_auto_snoozed
        FireOutcome.SKIPPED -> R.string.health_outcome_skipped
        FireOutcome.SUPERSEDED -> R.string.health_outcome_superseded
        FireOutcome.MISSED -> R.string.health_outcome_missed
    },
)
