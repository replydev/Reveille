package com.replydev.reveille.ui.home

import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Occurrence
import java.time.Instant

/** What the Home "Next alarm" card says, decided from what is armed with AlarmManager and what the schedule expects. */
sealed interface NextUp {
    data object None : NextUp

    /** The armed alarm's time has passed: it is ringing, about to be caught up, or was not delivered. */
    data class Overdue(val armed: Armed) : NextUp

    /** [earlierUnarmed] is a scheduled alarm before [armed] that the system does not know about. */
    data class Set(val armed: Armed, val earlierUnarmed: Occurrence?) : NextUp

    /** The schedule has an alarm but nothing is armed. */
    data class NotSet(val scheduled: Occurrence) : NextUp
}

/** [scheduled] is the first non-skipped occurrence after [now]. A snooze legitimately rings out of schedule order. */
fun nextUp(armed: Armed?, scheduled: Occurrence?, now: Instant): NextUp = when {
    armed != null && !armed.at.isAfter(now) -> NextUp.Overdue(armed)
    armed != null -> NextUp.Set(armed, scheduled?.takeIf { !armed.isSnooze && it.at.toInstant().isBefore(armed.at) })
    scheduled != null -> NextUp.NotSet(scheduled)
    else -> NextUp.None
}
