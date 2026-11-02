package com.replydev.reveille.update

import com.replydev.reveille.engine.Armed
import java.time.Duration
import java.time.Instant

sealed interface GuardResult {
    data object Allowed : GuardResult

    /** The next alarm or snooze is due within [InstallGuard.WINDOW] (or overdue): installing could restart under it. */
    data class AlarmSoon(val armed: Armed) : GuardResult

    /** Something is ringing, or claimed and not yet resolved. Never installable, not even with "Install anyway". */
    data class Ringing(val armed: Armed) : GuardResult
}

/** Installing kills and restarts the app: never while an alarm rings, and only on request close to the next one. */
object InstallGuard {
    val WINDOW: Duration = Duration.ofMinutes(30)

    /**
     * @param ringing the alarm that is ringing now, or whose ring is claimed but unresolved (the ring guard).
     */
    fun check(armed: Armed?, snooze: Armed?, ringing: Armed?, now: Instant): GuardResult {
        if (ringing != null) return GuardResult.Ringing(ringing)
        val next = listOfNotNull(armed, snooze).minByOrNull { it.at } ?: return GuardResult.Allowed
        return if (next.at.isAfter(now.plus(WINDOW))) GuardResult.Allowed else GuardResult.AlarmSoon(next)
    }
}

/**
 * Whether Today shows the update card for [nudge]: never while Health reports a failure (or hasn't loaded yet,
 * [failingChecks] null), so Health always wins the user's attention, and never inside the guard window of an alarm.
 */
fun showUpdateNotice(nudge: Release?, failingChecks: Int?, guard: GuardResult): Boolean =
    nudge != null && failingChecks == 0 && guard == GuardResult.Allowed
