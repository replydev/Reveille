package com.replydev.reveille.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [armed] is what to register, [triggerAt] when AlarmManager should fire it, [missed] every overdue alarm that is
 * too old to ring, oldest first. The caller records each missed entry so it becomes handled.
 */
data class ArmDecision(val armed: Armed?, val triggerAt: Instant?, val missed: List<Armed>)

object ArmPlanner {
    /** An alarm that should have rung up to this long ago still rings (e.g. after a reboot). */
    val LATE_RING_WINDOW: Duration = Duration.ofMinutes(30)

    /** The backup alarm fires this long after the primary. */
    val BACKUP_DELAY: Duration = Duration.ofMinutes(2)

    /** Catch-up alarms are re-armed this far in the future so they go through AlarmManager. */
    val CATCH_UP_DELAY: Duration = Duration.ofSeconds(1)

    /**
     * Decides what to arm now.
     *
     * Overdue alarms are every occurrence the current [snapshot] has between [previous]'s time and [now] (so a
     * deleted one-off is never rung), plus a past [snooze], minus those [isHandled] reports as fired, skipped or
     * already reported. The earliest one no older than [LATE_RING_WINDOW] is caught up in [CATCH_UP_DELAY]; older
     * ones are [ArmDecision.missed]. With nothing late, the earliest of the next occurrence and a future snooze is
     * armed at its own time.
     *
     * [previous] is used even when already handled, so after a catch-up the next decision still sees later
     * overdue alarms from the same outage.
     */
    fun decide(
        snapshot: ScheduleSnapshot,
        zone: ZoneId,
        now: Instant,
        previous: Armed?,
        snooze: Armed?,
        isHandled: (Armed) -> Boolean,
    ): ArmDecision {
        val overdueFromSchedule = previous
            ?.takeIf { !it.at.isAfter(now) }
            ?.let { ScheduleEngine.between(snapshot, it.at, now, zone).map(Occurrence::toArmed) }
            .orEmpty()
        val overdue = (overdueFromSchedule + listOfNotNull(snooze?.takeIf { !it.at.isAfter(now) }))
            .distinctBy { it.key }
            .filterNot(isHandled)
            .sortedBy { it.at }
        val (late, missed) = overdue.partition { Duration.between(it.at, now) <= LATE_RING_WINDOW }
        late.firstOrNull()?.let { return ArmDecision(it, now.plus(CATCH_UP_DELAY), missed) }

        val next = ScheduleEngine.upcoming(snapshot, now, zone, 1).firstOrNull()?.toArmed()
        val armed = listOfNotNull(next, snooze?.takeIf { it.at.isAfter(now) }).minByOrNull { it.at }
        return ArmDecision(armed, armed?.at, missed)
    }
}
