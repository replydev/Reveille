package com.replydev.reveille.alarm

import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Source

/**
 * How the ringing screen looks (docs/design/ringing-themes.md). Cosmetic only: sound, vibration, snooze and
 * auto-snooze are the same in every theme. Stored by [name]; anything unreadable is [DAWN].
 */
enum class RingTheme {
    DAWN,
    MIDNIGHT,
    POLICY_COLOUR,
    HORIZON,
    PAPER,
    ;

    companion object {
        fun fromName(name: String?): RingTheme = entries.firstOrNull { it.name == name } ?: DAWN
    }
}

/** The policy an alarm (or the alarm a snooze snoozes) comes from, or null for one-offs, the test alarm and unknown keys. */
fun ringPolicyId(armed: Armed): Long? =
    (listOf(armed.key) + armed.sourceKeys).firstNotNullOfOrNull { (Source.parse(it) as? Source.PolicyRule)?.policyId }

/** A policy alarm (or its snooze) uses its policy's override if there is one; everything else uses [default]. */
fun resolveTheme(armed: Armed, default: RingTheme, overrides: Map<Long, RingTheme>): RingTheme =
    ringPolicyId(armed)?.let(overrides::get) ?: default
