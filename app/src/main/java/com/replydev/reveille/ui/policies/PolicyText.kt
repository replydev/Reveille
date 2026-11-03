package com.replydev.reveille.ui.policies

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.engine.Occurrence
import com.replydev.reveille.engine.Policy
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source
import com.replydev.reveille.text.Labels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The policy card's status line: the rendered [policyStatus] when there is one, otherwise "Active · next {day HH:MM}". */
fun statusLine(res: Resources, status: String?, next: String?): String =
    status ?: next?.let { res.getString(R.string.policy_active_next, it) } ?: res.getString(R.string.policy_active)

/** Pause statuses are drawn in the accent; the rest (off, ended, not started yet) stay muted. */
fun isPauseStatus(status: PolicyStatus?): Boolean = status is PolicyStatus.Paused || status is PolicyStatus.PausedUntilEnd

/** The right-aligned note of a rule row: "every N wk" and/or its label. */
fun ruleNote(res: Resources, rule: RuleEntity): String? =
    listOfNotNull(
        rule.intervalWeeks.takeIf { it > 1 }?.let { res.getQuantityString(R.plurals.policy_every_n_weeks, it, it) },
        rule.label?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { null }

/**
 * The next time [policy] rings within [POLICY_LOOKAHEAD_DAYS], judged against the rest of [snapshot] (its skips and
 * the one-offs that replace a day), or null.
 */
fun nextPolicyAlarm(policy: Policy, snapshot: ScheduleSnapshot, from: Instant, zone: ZoneId): Occurrence? =
    ScheduleEngine
        .upcoming(snapshot.copy(policies = listOf(policy)), from, zone, limit = Int.MAX_VALUE, horizonDays = POLICY_LOOKAHEAD_DAYS - 1)
        .firstOrNull { occurrence -> occurrence.sources.any { it is Source.PolicyRule } }

/** "Tomorrow, 07:55" or "Thu, 5 Nov, 06:30" (en-GB), for the editor's "Next:". */
fun nextText(labels: Labels, at: ZonedDateTime, today: LocalDate): String =
    labels.res.getString(R.string.policy_next_text, labels.dayHeader(at.toLocalDate(), today), labels.formats.time(at.toLocalTime()))
