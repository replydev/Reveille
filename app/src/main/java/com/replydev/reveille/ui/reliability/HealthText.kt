package com.replydev.reveille.ui.reliability

import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.engine.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The line under the Health title: "X of Y checks pass", and the verdict beside it. */
data class HealthSummary(val text: String, val verdict: String, val allGood: Boolean)

fun healthSummary(passing: Int, total: Int): HealthSummary {
    val allGood = passing == total
    return HealthSummary("$passing of $total checks pass", if (allGood) "All good" else "Alarms may not ring", allGood)
}

/** The muted note on a passing check: "HyperOS" for the Xiaomi-only checks, "Confirmed" for other manual ones. */
fun passingNote(id: String, manual: Boolean): String? = when {
    id.startsWith("miui_") -> "HyperOS"
    manual -> "Confirmed"
    else -> null
}

/** "Today 07:55", "Fri 30 Oct 08:55": when a logged alarm was due. */
fun logTime(scheduledAt: Long, today: LocalDate, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(scheduledAt).atZone(zone)
    return "${TimeText.dayHeaderText(at.toLocalDate(), today)} ${TimeText.time(at.toLocalTime())}"
}

/** FIRED means rung but never dismissed or snoozed; every other value reads fine as its own name. */
fun outcomeText(outcome: FireOutcome): String = when (outcome) {
    FireOutcome.FIRED -> "rang, not answered yet"
    else -> outcome.name.lowercase().replace('_', ' ')
}
