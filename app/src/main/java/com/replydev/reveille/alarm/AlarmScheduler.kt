package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.replydev.reveille.MainActivity
import com.replydev.reveille.data.FireLogDao
import com.replydev.reveille.data.FireLogEntity
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.engine.ArmPlanner
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class AlarmScheduler(
    private val context: Context,
    private val repository: ScheduleRepository,
    private val fireLog: FireLogDao,
    private val state: SchedulerState,
    private val notifier: Notifier,
    private val onArmedChanged: suspend () -> Unit,
    private val clock: () -> Instant = Instant::now,
    /** Whether [RingingService] rings this occurrence in this process (it then keeps the guard armed itself). */
    private val isRinging: (Armed) -> Boolean = RingingService::isRinging,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val guardMutex = Mutex()

    /**
     * Recomputes and registers the next alarm. Safe to call from anywhere, any number of times. [onArmedChanged] runs
     * after the lock is released, so it may re-arm itself and never holds re-arms up.
     */
    suspend fun rearm(reason: String) {
        mutex.withLock { rearmLocked(reason) }
        onArmedChanged()
    }

    /** The body of [rearm]; the caller holds [mutex]. */
    private suspend fun rearmLocked(reason: String) {
        val now = clock()
        val zone = ZoneId.systemDefault()
        val previous = state.armed()
        val snooze = state.snooze()
        val since = listOfNotNull(previous?.at, snooze?.at, now).min()
        val handled = fireLog.handledSince(since.toEpochMilli()).mapTo(HashSet()) { it.occurrenceKey to it.scheduledAt }
        val decision = ArmPlanner.decide(repository.snapshot(), zone, now, previous, snooze) { (it.key to it.at.toEpochMilli()) in handled }

        decision.missed.forEach { recordMissed(it) }
        if (decision.missed.any { it.isSnooze }) state.setSnooze(null)
        restoreGuard(now)
        // Only after overdue alarms were decided: data from the outage window must still exist while deciding.
        // atZone, not LocalDate.ofInstant: the latter is API 34 and crashed rearm on Android 12/13.
        repository.cleanup(since.atZone(zone).toLocalDate().minusDays(1))
        // Same rule for the fire log: never prune rows this decision may still have needed.
        fireLog.deleteScheduledBefore(minOf(since, now.minus(FIRE_LOG_RETENTION)).toEpochMilli())
        val armed = decision.armed
        val trigger = decision.triggerAt
        if (armed != null && trigger != null) {
            // Not persisted when AlarmManager refused it: the next rearm must still see the previous alarm.
            if (arm(armed, trigger)) state.setArmed(armed)
        } else {
            cancel()
            state.setArmed(null)
        }
        Log.i(TAG, "rearm($reason): armed=${armed?.key} trigger=$trigger missed=${decision.missed.map { it.key }}")
    }

    /** Returns true only for the first delivery of [armed]; later deliveries (backup, catch-up) return false. */
    suspend fun claim(armed: Armed): Boolean =
        fireLog.insert(log(armed, FireOutcome.FIRED, firedAt = clock().toEpochMilli())) != -1L

    /** The logged outcome of [armed], or null if it was never logged. */
    suspend fun outcome(armed: Armed): FireOutcome? = fireLog.outcome(armed.key, armed.at.toEpochMilli())

    /**
     * The user dismissed a fallback alarm notification: the occurrence is handled and must not be retried. An
     * occurrence already given up as MISSED stays MISSED (the log must not claim the user dismissed it in time).
     */
    suspend fun acknowledgeFallback(armed: Armed) {
        if (outcome(armed) != FireOutcome.MISSED) recordOutcome(armed, FireOutcome.DISMISSED)
        if (armed.isSnooze && state.snooze()?.key == armed.key) state.setSnooze(null)
    }

    /** Sets [armed]'s outcome, logging the occurrence first if it was never claimed (e.g. the claim itself failed). */
    suspend fun recordOutcome(armed: Armed, outcome: FireOutcome) {
        val firedAt = if (outcome == FireOutcome.MISSED || outcome == FireOutcome.SKIPPED) null else clock().toEpochMilli()
        if (fireLog.insert(log(armed, outcome, firedAt)) == -1L) {
            fireLog.setOutcome(armed.key, armed.at.toEpochMilli(), outcome)
        }
    }

    /** Gives up on an occurrence that never got handled: records it MISSED and tells the user. */
    suspend fun markMissed(armed: Armed) {
        recordOutcome(armed, FireOutcome.MISSED)
        notifier.showMissed(armed)
    }

    /**
     * Arms the ring guard for [armed] at [at]: an extra `setAlarmClock` alarm that re-rings the occurrence if it is
     * still unhandled then (see [AlarmFiring.onGuard]). Only one occurrence is guarded: if the guard moves away from
     * another one that is still FIRED, that one is recorded [FireOutcome.SUPERSEDED].
     *
     * Why a single slot: only one alarm can ring at a time, and the newer one is what the user needs to act on. One
     * PendingIntent (fixed request code) is also trivially cancellable and can never pile up stale guards. The cost:
     * an older unhandled occurrence loses its retries once superseded (its fallback notification, if posted, stays),
     * which only matters if two alarms are due within minutes of each other and both fail to ring.
     */
    suspend fun armGuard(armed: Armed, attempt: Int, at: Instant): Unit = guardMutex.withLock {
        setGuardAlarm(armed, attempt, at)
        val previous = state.guard()
        if (previous != null && previous.isSameOccurrence(armed)) return@withLock
        if (previous != null && outcome(previous) == FireOutcome.FIRED) recordOutcome(previous, FireOutcome.SUPERSEDED)
        state.setGuard(armed)
    }

    /**
     * Force-stop recovery. A force-stop (or an OEM kill that clears alarms) deletes the guard alarm but not its persisted
     * owner. If that owner is still FIRED and not ringing in this process, it is unresolved: re-arm its guard shortly
     * while inside the late window, otherwise report it missed and withdraw its fallback notification.
     */
    private suspend fun restoreGuard(now: Instant): Unit = guardMutex.withLock {
        val owner = state.guard() ?: return@withLock
        if (isRinging(owner) || outcome(owner) != FireOutcome.FIRED) return@withLock
        if (Duration.between(owner.at, now) <= ArmPlanner.LATE_RING_WINDOW) {
            setGuardAlarm(owner, 1, now.plus(GUARD_RESTORE_DELAY))
        } else {
            Log.w(TAG, "Unresolved ${owner.key} is past the late window; reporting missed")
            recordOutcome(owner, FireOutcome.MISSED)
            notifySafely("missed ${owner.key}") { notifier.showMissed(owner) }
            notifySafely("cancel fallback ${owner.key}") { notifier.cancelFallbackAlarm(owner) }
            alarmManager.cancel(guardIntent(null, 0))
            state.setGuard(null)
        }
    }

    /** Pushes the guard of a still-ringing [armed] to [at]. Returns false if another occurrence took the guard over. */
    suspend fun refreshGuard(armed: Armed, at: Instant): Boolean = guardMutex.withLock {
        val guarded = state.guard()
        if (guarded != null && !guarded.isSameOccurrence(armed)) return@withLock false
        setGuardAlarm(armed, 0, at)
        if (guarded == null) state.setGuard(armed)
        true
    }

    /** Cancels the guard if it protects [armed]; a guard for another occurrence is left alone. */
    suspend fun cancelGuard(armed: Armed): Unit = guardMutex.withLock {
        val guarded = state.guard() ?: return@withLock
        if (!guarded.isSameOccurrence(armed)) return@withLock
        alarmManager.cancel(guardIntent(null, 0))
        state.setGuard(null)
    }

    /**
     * Skips the armed alarm (widget "Skip" button), but only if it is still the occurrence [expectedKey] at
     * [expectedAt] that the caller displayed: a stale tap (e.g. a double tap before the widget refreshed) is a no-op
     * instead of skipping the following alarm too. The check and the skip happen under the re-arm lock.
     *
     * A policy alarm is skipped for its policy's whole date (see [ScheduleRepository.skipOccurrence]); a one-off is
     * deleted; a snooze is cleared. The ring guard is not touched: an alarm that is ringing keeps its guard until it is
     * dismissed or snoozed.
     */
    suspend fun skipNext(expectedKey: String, expectedAt: Instant) {
        val skipped = mutex.withLock {
            val armed = state.armed()
            if (armed == null || armed.key != expectedKey || armed.at != expectedAt) return@withLock false
            // A past (catch-up) alarm must be marked handled, otherwise the planner would ring it again.
            if (!armed.at.isAfter(clock())) fireLog.insert(log(armed, FireOutcome.SKIPPED, firedAt = null))
            if (armed.isSnooze || armed.sourceKeys.isEmpty()) {
                state.setSnooze(null)
                cancelDirectSnooze()
            } else {
                repository.skipOccurrence(armed.sourceKeys, notify = false)
            }
            // Always re-arm, even if the sources were gone already: the armed alarm must move on.
            rearmLocked("skipped next")
            true
        }
        if (skipped) onArmedChanged()
    }

    private suspend fun recordMissed(missed: Armed) {
        fireLog.insert(log(missed, FireOutcome.MISSED, firedAt = null))
        notifySafely("missed ${missed.key}") { notifier.showMissed(missed) }
    }

    /** Runs a notifier call on the re-arm path: a failing notification is logged and must never abort the re-arm. */
    private inline fun notifySafely(what: String, block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "Notification ($what) failed", e)
    }

    /**
     * Arms [snooze] straight with AlarmManager under its own request code, bypassing the planner: used when the snooze
     * could not be persisted, so it still rings. Delivered like any alarm; the claim dedupes it against the planner's.
     */
    fun armDirectSnooze(snooze: Armed) {
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(snooze.at.toEpochMilli(), showIntent()), fireIntent(snooze, REQUEST_DIRECT_SNOOZE))
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarms not permitted; snooze ${snooze.key} not armed", e)
        }
    }

    fun cancelDirectSnooze() = alarmManager.cancel(fireIntent(null, REQUEST_DIRECT_SNOOZE))

    private fun setGuardAlarm(armed: Armed, attempt: Int, at: Instant) {
        try {
            // The guard shows up as the system's "next alarm" while an occurrence is unresolved; tapping it opens the
            // ringing screen rather than the app.
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), ringingShowIntent()), guardIntent(armed, attempt))
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarms not permitted; ring guard for ${armed.key} not armed", e)
        }
    }

    private fun guardIntent(armed: Armed?, attempt: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_GUARD)
            .putExtra(EXTRA_ATTEMPT, attempt)
        if (armed != null) intent.putExtra(EXTRA_ARMED, armed.encode())
        return PendingIntent.getBroadcast(context, REQUEST_GUARD, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun log(armed: Armed, outcome: FireOutcome, firedAt: Long?) = FireLogEntity(
        occurrenceKey = armed.key,
        label = armed.label,
        scheduledAt = armed.at.toEpochMilli(),
        firedAt = firedAt,
        outcome = outcome,
    )

    /** Returns false if the system refused exact alarms. */
    private fun arm(armed: Armed, trigger: Instant): Boolean {
        return try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger.toEpochMilli(), showIntent()), fireIntent(armed, REQUEST_PRIMARY))
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.plus(ArmPlanner.BACKUP_DELAY).toEpochMilli(), showIntent()),
                fireIntent(armed, REQUEST_BACKUP),
            )
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarms not permitted", e)
            notifySafely("scheduling failed") { notifier.showSchedulingFailed() }
            false
        }
    }

    private fun cancel() {
        alarmManager.cancel(fireIntent(null, REQUEST_PRIMARY))
        alarmManager.cancel(fireIntent(null, REQUEST_BACKUP))
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SHOW,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ringingShowIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_GUARD_SHOW,
        Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun fireIntent(armed: Armed?, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_BACKUP, requestCode == REQUEST_BACKUP)
        if (armed != null) intent.putExtra(EXTRA_ARMED, armed.encode())
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_FIRE = "com.replydev.reveille.FIRE"
        const val ACTION_GUARD = "com.replydev.reveille.GUARD"
        const val EXTRA_ARMED = "armed"
        const val EXTRA_BACKUP = "backup"
        const val EXTRA_ATTEMPT = "attempt"
        private const val REQUEST_PRIMARY = 100
        private const val REQUEST_BACKUP = 101
        private const val REQUEST_SHOW = 102
        private const val REQUEST_GUARD = 103
        private const val REQUEST_DIRECT_SNOOZE = 104
        private val GUARD_RESTORE_DELAY: Duration = Duration.ofSeconds(10)
        private val FIRE_LOG_RETENTION: Duration = Duration.ofDays(60)
        private const val REQUEST_GUARD_SHOW = 105
        private const val TAG = "Reveille"
    }
}

/** Same scheduled occurrence (label and sources may differ between encodings). */
fun Armed.isSameOccurrence(other: Armed): Boolean = key == other.key && at == other.at
