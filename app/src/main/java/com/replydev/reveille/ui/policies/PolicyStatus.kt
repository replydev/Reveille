package com.replydev.reveille.ui.policies

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.text.Labels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Days the editor looks ahead when warning that a policy will not ring; matches the Home timeline. */
const val POLICY_LOOKAHEAD_DAYS = 14

/** "23 Dec", or "6 Jan 2027" outside the current year. */
fun shortDate(labels: Labels, date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) labels.formats.dayMonth(date) else labels.formats.fullDate(date)

/** Why a policy does not ring today. */
sealed interface PolicyStatus {
    data object Off : PolicyStatus
    data class Ended(val on: LocalDate) : PolicyStatus
    data class Starts(val on: LocalDate) : PolicyStatus
    /** Paused today; rings again on [resumes]. */
    data class Paused(val resumes: LocalDate) : PolicyStatus
    /** Paused from today until past [end], so it never rings again. */
    data class PausedUntilEnd(val end: LocalDate) : PolicyStatus
}

/** "Off", "Ended 3 Oct", "Starts 10 Nov", "Paused, resumes 6 Jan 2027", "Paused until it ends on 4 Nov". */
fun PolicyStatus.text(labels: Labels, today: LocalDate): String = when (this) {
    PolicyStatus.Off -> labels.res.getString(R.string.policy_status_off)
    is PolicyStatus.Ended -> labels.res.getString(R.string.policy_status_ended, shortDate(labels, on, today))
    is PolicyStatus.Starts -> labels.res.getString(R.string.policy_status_starts, shortDate(labels, on, today))
    is PolicyStatus.Paused -> labels.res.getString(R.string.policy_status_paused, shortDate(labels, resumes, today))
    is PolicyStatus.PausedUntilEnd -> labels.res.getString(R.string.policy_status_paused_until_end, shortDate(labels, end, today))
}

/** Why a policy does not ring today, or null if it is active. */
fun policyStatus(policy: PolicyEntity, pauses: List<PauseEntity>, today: LocalDate): PolicyStatus? {
    val start = policy.startDate
    val end = policy.endDate
    return when {
        !policy.enabled -> PolicyStatus.Off
        end != null && end.isBefore(today) -> PolicyStatus.Ended(end)
        start != null && start.isAfter(today) -> PolicyStatus.Starts(start)
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
                end != null && resume.isAfter(end) -> PolicyStatus.PausedUntilEnd(end)
                else -> PolicyStatus.Paused(resume)
            }
        }
    }
}

/** A non-blocking warning for the editor when the draft policy would not ring in the next [POLICY_LOOKAHEAD_DAYS] days. */
fun upcomingWarning(res: Resources, policy: PolicyEntity, rules: List<RuleEntity>, pauses: List<PauseEntity>, from: Instant, zone: ZoneId): String? {
    if (rules.isEmpty()) return null
    if (!policy.enabled) return res.getString(R.string.policy_warning_off)
    val snapshot = ScheduleSnapshot(policies = listOf(PolicyWithChildren(policy, rules, pauses).toModel()))
    val next = ScheduleEngine.upcoming(snapshot, from, zone, limit = 1, horizonDays = POLICY_LOOKAHEAD_DAYS - 1)
    return if (next.isEmpty()) res.getQuantityString(R.plurals.policy_warning_no_alarms, POLICY_LOOKAHEAD_DAYS, POLICY_LOOKAHEAD_DAYS) else null
}
