package com.replydev.reveille.engine

import java.time.Duration
import java.time.Instant

/** [armed] is what to register, [triggerAt] when AlarmManager should fire it, [missed] an alarm to report as missed. */
data class ArmDecision(val armed: Armed?, val triggerAt: Instant?, val missed: Armed?)

object ArmPlanner {
    /** An alarm that should have rung up to this long ago still rings (e.g. after a reboot). */
    val LATE_RING_WINDOW: Duration = Duration.ofMinutes(30)

    /** The backup alarm fires this long after the primary. */
    val BACKUP_DELAY: Duration = Duration.ofMinutes(2)

    /** Catch-up alarms are re-armed this far in the future so they go through AlarmManager. */
    val CATCH_UP_DELAY: Duration = Duration.ofSeconds(1)

    fun decide(previous: Armed?, previousHandled: Boolean, next: Armed?, snooze: Armed?, now: Instant): ArmDecision {
        var missed: Armed? = null
        if (previous != null && !previousHandled && !previous.at.isAfter(now)) {
            if (Duration.between(previous.at, now) <= LATE_RING_WINDOW) {
                return ArmDecision(previous, now.plus(CATCH_UP_DELAY), null)
            }
            missed = previous
        }
        val upcoming = listOfNotNull(next, snooze?.takeIf { it.at.isAfter(now) }).minByOrNull { it.at }
        return ArmDecision(upcoming, upcoming?.at, missed)
    }
}
