package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.engine.Occurrence
import com.replydev.reveille.engine.Policy
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The policy card's status line: [policyStatus] when there is one, otherwise "Active · next {day HH:MM}". */
fun statusLine(status: String?, next: String?): String = status ?: next?.let { "Active · next $it" } ?: "Active"

/** Pause statuses are drawn in the accent; the rest ("Off", "Ended …", "Starts …") stay muted. */
fun isPauseStatus(status: String?): Boolean = status?.startsWith("Paused") == true

/** The right-aligned note of a rule row: "every N wk" and/or its label. */
fun ruleNote(rule: RuleEntity): String? =
    listOfNotNull(rule.intervalWeeks.takeIf { it > 1 }?.let { "every $it wk" }, rule.label?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifEmpty { null }

/**
 * The next time [policy] rings within [POLICY_LOOKAHEAD_DAYS], judged against the rest of [snapshot] (its skips and
 * the one-offs that replace a day), or null.
 */
fun nextPolicyAlarm(policy: Policy, snapshot: ScheduleSnapshot, from: Instant, zone: ZoneId): Occurrence? =
    ScheduleEngine
        .upcoming(snapshot.copy(policies = listOf(policy)), from, zone, limit = Int.MAX_VALUE, horizonDays = POLICY_LOOKAHEAD_DAYS - 1)
        .firstOrNull { occurrence -> occurrence.sources.any { it is Source.PolicyRule } }

/** "Tomorrow, 07:55" or "Thu 5 Nov, 06:30", for the editor's "Next:". */
fun nextText(at: ZonedDateTime, today: LocalDate): String = "${TimeText.dayHeaderText(at.toLocalDate(), today)}, ${TimeText.time(at.toLocalTime())}"
