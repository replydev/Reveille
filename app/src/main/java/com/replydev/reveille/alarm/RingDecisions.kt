package com.replydev.reveille.alarm

import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.engine.ArmPlanner
import java.time.Duration
import java.time.Instant

/** What [RingingService] does with a RING delivery. */
enum class Delivery { RING, DUPLICATE }

/** What [AlarmReceiver] does when the ring guard fires (and the occurrence is not ringing in this process). */
enum class GuardAction {
    /** Still unhandled and inside the late window: start ringing again. */
    RING,

    /** Still unhandled but too late: record MISSED and say so. */
    GIVE_UP,

    /** Already handled: nothing to do. */
    DONE,
}

/** Pure decisions of the firing path, kept free of Android types so they are unit-testable. */
object RingDecisions {
    /** While ringing, the service keeps the guard this far in the future… */
    val GUARD_WHILE_RINGING: Duration = Duration.ofSeconds(90)

    /** …and pushes it forward this often, so it only fires if the service died. */
    val GUARD_REFRESH: Duration = Duration.ofSeconds(60)

    /** A ring never lasts longer than this before auto-snoozing, whatever the setting says. */
    const val MAX_RING_MINUTES = 30

    /** Auto-snoozes in a row before the alarm is given up as missed. */
    const val MAX_AUTO_SNOOZES = 3

    /** FIRED (claimed, not handled) or unknown (never logged / unreadable) both mean: it may still need to ring. */
    fun isUnhandled(outcome: FireOutcome?): Boolean = outcome == null || outcome == FireOutcome.FIRED

    /**
     * [claimed] is the result of claiming the occurrence: true for the first delivery, false for a later one (backup,
     * catch-up, guard), null if the claim failed or timed out. Ringing twice beats not ringing, so unknown rings.
     * A guard re-ring is a later delivery by design: it rings while the occurrence is still unhandled.
     */
    fun delivery(claimed: Boolean?, guard: Boolean, outcome: FireOutcome?): Delivery = when {
        claimed != false -> Delivery.RING
        guard && isUnhandled(outcome) -> Delivery.RING
        else -> Delivery.DUPLICATE
    }

    fun guard(outcome: FireOutcome?, scheduledAt: Instant, now: Instant): GuardAction = when {
        !isUnhandled(outcome) -> GuardAction.DONE
        Duration.between(scheduledAt, now) <= ArmPlanner.LATE_RING_WINDOW -> GuardAction.RING
        else -> GuardAction.GIVE_UP
    }

    /** Delay before guard retry number [attempt] (1-based) after the service could not be started: 10 s, 30 s, then 60 s. */
    fun guardBackoff(attempt: Int): Duration = when {
        attempt <= 1 -> Duration.ofSeconds(10)
        attempt == 2 -> Duration.ofSeconds(30)
        else -> Duration.ofSeconds(60)
    }

    fun ringMinutes(setting: Int): Int = setting.coerceIn(1, MAX_RING_MINUTES)
}
