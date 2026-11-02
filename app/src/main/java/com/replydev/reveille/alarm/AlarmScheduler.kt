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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AlarmScheduler(
    private val context: Context,
    private val repository: ScheduleRepository,
    private val fireLog: FireLogDao,
    private val state: SchedulerState,
    private val notifier: Notifier,
    private val onArmedChanged: suspend () -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    /** Recomputes and registers the next alarm. Safe to call from anywhere, any number of times. */
    suspend fun rearm(reason: String): Unit = mutex.withLock {
        val now = clock()
        val zone = ZoneId.systemDefault()
        val previous = state.armed()
        val snooze = state.snooze()
        val since = listOfNotNull(previous?.at, snooze?.at, now).min()
        val handled = fireLog.handledSince(since.toEpochMilli()).mapTo(HashSet()) { it.occurrenceKey to it.scheduledAt }
        val decision = ArmPlanner.decide(repository.snapshot(), zone, now, previous, snooze) { (it.key to it.at.toEpochMilli()) in handled }

        decision.missed.forEach { recordMissed(it) }
        if (decision.missed.any { it.isSnooze }) state.setSnooze(null)
        // Only after overdue alarms were decided: data from the outage window must still exist while deciding.
        repository.cleanup(LocalDate.ofInstant(since, zone).minusDays(1))
        val armed = decision.armed
        val trigger = decision.triggerAt
        if (armed != null && trigger != null) arm(armed, trigger) else cancel()
        state.setArmed(armed)
        Log.i(TAG, "rearm($reason): armed=${armed?.key} trigger=$trigger missed=${decision.missed.map { it.key }}")
        onArmedChanged()
    }

    /** Returns true only for the first delivery of [armed]; later deliveries (backup, catch-up) return false. */
    suspend fun claim(armed: Armed): Boolean =
        fireLog.insert(log(armed, FireOutcome.FIRED, firedAt = clock().toEpochMilli())) != -1L

    /**
     * Releases a [claim] whose ringing never started, so the next delivery can claim it again. The re-arm that
     * follows sees the occurrence unhandled and catches it up (now + 1 s) until [ArmPlanner.LATE_RING_WINDOW] has
     * passed; then it is reported missed. Never removes a row the user already acted on.
     */
    suspend fun unclaim(armed: Armed) = fireLog.delete(armed.key, armed.at.toEpochMilli())

    /** The user dismissed a fallback alarm notification: the occurrence is handled and must not be retried. */
    suspend fun acknowledgeFallback(armed: Armed) {
        if (fireLog.insert(log(armed, FireOutcome.DISMISSED, firedAt = clock().toEpochMilli())) == -1L) {
            recordOutcome(armed, FireOutcome.DISMISSED)
        }
        if (armed.isSnooze && state.snooze()?.key == armed.key) state.setSnooze(null)
    }

    suspend fun recordOutcome(armed: Armed, outcome: FireOutcome) =
        fireLog.setOutcome(armed.key, armed.at.toEpochMilli(), outcome)

    /** Skips the currently armed alarm (widget "Skip" button). */
    suspend fun skipNext() {
        val armed = state.armed() ?: return
        // A past (catch-up) alarm must be marked handled, otherwise the planner would ring it again.
        if (!armed.at.isAfter(clock())) fireLog.insert(log(armed, FireOutcome.SKIPPED, firedAt = null))
        if (armed.isSnooze || armed.sourceKeys.isEmpty()) {
            state.setSnooze(null)
            rearm("skipped next")
        } else {
            repository.skipOccurrence(armed.sourceKeys)
        }
    }

    private suspend fun recordMissed(missed: Armed) {
        fireLog.insert(log(missed, FireOutcome.MISSED, firedAt = null))
        notifier.showMissed(missed)
    }

    private fun log(armed: Armed, outcome: FireOutcome, firedAt: Long?) = FireLogEntity(
        occurrenceKey = armed.key,
        label = armed.label,
        scheduledAt = armed.at.toEpochMilli(),
        firedAt = firedAt,
        outcome = outcome,
    )

    private fun arm(armed: Armed, trigger: Instant) {
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger.toEpochMilli(), showIntent()), fireIntent(armed, REQUEST_PRIMARY))
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.plus(ArmPlanner.BACKUP_DELAY).toEpochMilli(), showIntent()),
                fireIntent(armed, REQUEST_BACKUP),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarms not permitted", e)
            notifier.showSchedulingFailed("Reveille is not allowed to schedule exact alarms. Open Reveille → Reliability.")
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

    private fun fireIntent(armed: Armed?, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_BACKUP, requestCode == REQUEST_BACKUP)
        if (armed != null) intent.putExtra(EXTRA_ARMED, armed.encode())
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_FIRE = "com.replydev.reveille.FIRE"
        const val EXTRA_ARMED = "armed"
        const val EXTRA_BACKUP = "backup"
        private const val REQUEST_PRIMARY = 100
        private const val REQUEST_BACKUP = 101
        private const val REQUEST_SHOW = 102
        private const val TAG = "Reveille"
    }
}
