package com.replydev.reveille.ui.home

import com.replydev.reveille.engine.ArmPlanner
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.CHECKS_FAILED
import java.time.Instant
import java.time.ZoneId

/** The reliability pill: "1 issue", "3 issues", or "Check failed" when the checks could not run. */
fun issuesLabel(failing: Int): String = when (failing) {
    CHECKS_FAILED -> "Check failed"
    1 -> "1 issue"
    else -> "$failing issues"
}

/** The time the backup alarm rings if the primary is not delivered ([ArmPlanner.BACKUP_DELAY] later). */
fun backupText(armedAt: Instant, zone: ZoneId): String =
    "backup ${TimeText.time(armedAt.plus(ArmPlanner.BACKUP_DELAY).atZone(zone).toLocalTime())}"

/** The day half of [TimeText.dayAndTime] ("Today", "Tomorrow", "Thu", "Tue 17 Nov"); its time never has a space. */
fun dayPart(at: Instant, now: Instant, zone: ZoneId): String = TimeText.dayAndTime(at, now, zone).substringBeforeLast(' ')

fun alarmCount(count: Int): String = if (count == 1) "1 alarm" else "$count alarms"

/** The muted note after a timeline label, or null: "skipped", "one-off", or "every N wk" from the rule's interval. */
fun occurrenceNote(sources: List<Source>, skipped: Boolean, intervalWeeks: (ruleId: Long) -> Int?): String? {
    if (skipped) return "skipped"
    val policy = sources.filterIsInstance<Source.PolicyRule>().firstOrNull() ?: return "one-off"
    val interval = intervalWeeks(policy.ruleId) ?: return null
    return if (interval > 1) "every $interval wk" else null
}

/** Where the hero card's second button leads for an armed alarm with these source keys. */
sealed interface HeroTarget {
    data class Policy(val id: Long) : HeroTarget
    data class OneOff(val id: Long) : HeroTarget
}

fun heroTarget(sourceKeys: List<String>): HeroTarget? {
    val sources = sourceKeys.mapNotNull(Source::parse)
    sources.filterIsInstance<Source.PolicyRule>().firstOrNull()?.let { return HeroTarget.Policy(it.policyId) }
    return sources.filterIsInstance<Source.OneOffAlarm>().firstOrNull()?.let { HeroTarget.OneOff(it.oneOffId) }
}
