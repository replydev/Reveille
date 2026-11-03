package com.replydev.reveille.alarm

import com.replydev.reveille.R
import com.replydev.reveille.engine.ArmPlanner
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.text.Labels
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Paper's progress bar: how far [ringingFor] is towards the auto-snooze after [ringMinutes], from 0 to 1. */
fun paperProgress(ringingFor: Duration, ringMinutes: Int): Float {
    val total = Duration.ofMinutes(ringMinutes.coerceAtLeast(0).toLong())
    if (total.isZero) return 1f
    return (ringingFor.toMillis().toFloat() / total.toMillis()).coerceIn(0f, 1f)
}

/** "0:42": minutes and seconds the alarm has rung (Paper's "Ringing for"). Digits only, in the mono face. */
fun ringElapsed(ringingFor: Duration): String {
    val seconds = ringingFor.seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** Midnight's second line: "Backup rings at 05:42", the backup alarm two minutes after [at]. */
fun backupRingText(labels: Labels, at: Instant, zone: ZoneId): String =
    labels.res.getString(R.string.ringing_backup_at, labels.formats.time(at.plus(ArmPlanner.BACKUP_DELAY).atZone(zone).toLocalTime()))

/** What Policy colour shows about the alarm's policy: its name, the days of the rule that rang, and its next alarm. */
data class PolicyRingInfo(val name: String, val days: Set<DayOfWeek>?, val next: Instant?)

/**
 * [PolicyRingInfo] for [armed]'s policy (or the policy of the alarm a snooze snoozes), or null for a one-off, the test
 * alarm, or a policy deleted meanwhile. The next alarm is the policy's first occurrence after [armed] and [now].
 */
fun policyRingInfo(snapshot: ScheduleSnapshot, armed: Armed, now: Instant, zone: ZoneId): PolicyRingInfo? {
    val source = ringPolicySource(armed) ?: return null
    val policy = snapshot.policies.firstOrNull { it.id == source.policyId } ?: return null
    val from = maxOf(armed.at, now)
    val next = ScheduleEngine.upcoming(ScheduleSnapshot(listOf(policy), skips = snapshot.skips), from, zone, limit = 1).firstOrNull()
    return PolicyRingInfo(policy.name, policy.rules.firstOrNull { it.id == source.ruleId }?.days, next?.at?.toInstant())
}
