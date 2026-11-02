package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Days the editor looks ahead when warning that a policy will not ring; matches the Home timeline. */
const val POLICY_LOOKAHEAD_DAYS = 14

private val sameYear = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** "23 Dec", or "6 Jan 2027" outside the current year. */
fun shortDate(date: LocalDate, today: LocalDate): String =
    date.format(if (date.year == today.year) sameYear else otherYear)

/** Why a policy does not ring today ("Off", "Ended 3 Oct", "Starts 10 Nov", "Paused, resumes 6 Jan"), or null if it is active. */
fun policyStatus(policy: PolicyEntity, pauses: List<PauseEntity>, today: LocalDate): String? {
    val start = policy.startDate
    val end = policy.endDate
    return when {
        !policy.enabled -> "Off"
        end != null && end.isBefore(today) -> "Ended ${shortDate(end, today)}"
        start != null && start.isAfter(today) -> "Starts ${shortDate(start, today)}"
        else -> {
            var resume = today
            while (true) {
                val day = resume
                val covering = pauses.filter { !day.isBefore(it.fromDate) && !day.isAfter(it.toDate) }
                if (covering.isEmpty()) break
                resume = covering.maxOf { it.toDate }.plusDays(1)
            }
            when {
                resume == today -> null
                end != null && resume.isAfter(end) -> "Paused until it ends on ${shortDate(end, today)}"
                else -> "Paused, resumes ${shortDate(resume, today)}"
            }
        }
    }
}

/** A non-blocking warning for the editor when the draft policy would not ring in the next [POLICY_LOOKAHEAD_DAYS] days. */
fun upcomingWarning(policy: PolicyEntity, rules: List<RuleEntity>, pauses: List<PauseEntity>, from: Instant, zone: ZoneId): String? {
    if (rules.isEmpty()) return null
    if (!policy.enabled) return "This policy is off: none of its alarms will ring."
    val snapshot = ScheduleSnapshot(policies = listOf(PolicyWithChildren(policy, rules, pauses).toModel()))
    val next = ScheduleEngine.upcoming(snapshot, from, zone, limit = 1, horizonDays = POLICY_LOOKAHEAD_DAYS - 1)
    return if (next.isEmpty()) "This policy has no alarms in the next $POLICY_LOOKAHEAD_DAYS days." else null
}
