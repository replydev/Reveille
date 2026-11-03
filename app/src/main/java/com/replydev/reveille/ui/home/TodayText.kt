package com.replydev.reveille.ui.home

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.engine.ArmPlanner
import com.replydev.reveille.engine.Source
import com.replydev.reveille.text.Labels
import com.replydev.reveille.ui.components.CHECKS_FAILED
import java.time.Instant
import java.time.ZoneId

/** The reliability pill: "1 issue", "3 issues", or "Check failed" when the checks could not run. */
fun issuesLabel(res: Resources, failing: Int): String =
    if (failing == CHECKS_FAILED) res.getString(R.string.today_check_failed)
    else res.getQuantityString(R.plurals.today_issues, failing, failing)

/** The time the backup alarm rings if the primary is not delivered ([ArmPlanner.BACKUP_DELAY] later). */
fun backupText(labels: Labels, armedAt: Instant, zone: ZoneId): String =
    labels.res.getString(R.string.today_backup, labels.formats.time(armedAt.plus(ArmPlanner.BACKUP_DELAY).atZone(zone).toLocalTime()))

fun alarmCount(res: Resources, count: Int): String = res.getQuantityString(R.plurals.today_alarm_count, count, count)

/** The muted note after a timeline label, or null: "skipped", "one-off", or "every N wk" from the rule's interval. */
fun occurrenceNote(res: Resources, sources: List<Source>, skipped: Boolean, intervalWeeks: (ruleId: Long) -> Int?): String? {
    if (skipped) return res.getString(R.string.today_note_skipped)
    val policy = sources.filterIsInstance<Source.PolicyRule>().firstOrNull() ?: return res.getString(R.string.today_note_one_off)
    val interval = intervalWeeks(policy.ruleId) ?: return null
    return if (interval > 1) res.getQuantityString(R.plurals.policy_every_n_weeks, interval, interval) else null
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
