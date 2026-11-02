package com.replydev.reveille.alarm

import android.util.Log
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.data.SettingsStore
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the firing path persists, shared by [AlarmReceiver] and [RingingService] and kept out of them so it can
 * be tested without a running service. Rule of thumb: when unsure, ring.
 *
 * @param startService asks the system for [RingingService] (armed, backup, guard). Returns false or throws when refused.
 * @param ringingNow the occurrence [RingingService] rings in this process, if any.
 * @param dismissRinging tells the running [RingingService] to dismiss what it rings.
 */
class AlarmFiring(
    private val scheduler: AlarmScheduler,
    private val state: SchedulerState,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val startService: (armed: Armed, backup: Boolean, guard: Boolean) -> Boolean,
    private val ringingNow: () -> Armed?,
    private val dismissRinging: () -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    fun isRinging(armed: Armed): Boolean = ringingNow()?.isSameOccurrence(armed) == true

    fun startRinging(armed: Armed, backup: Boolean, guard: Boolean): Boolean = try {
        startService(armed, backup, guard)
    } catch (e: Exception) {
        Log.e(TAG, "RingingService refused for ${armed.key}", e)
        false
    }

    // ---- Receiver side ----

    /**
     * [RingingService] could not be started (or could not go foreground) for [armed]. Claims the occurrence so the
     * planner does not catch it up in a loop, rings through the fallback notification and arms the ring guard, which
     * retries the service until the occurrence is handled or too late. Always re-arms. Never throws.
     */
    suspend fun onStartFailed(armed: Armed) {
        try {
            val claimed = safely("claim ${armed.key}", true) { scheduler.claim(armed) }
            val outcome = if (claimed) FireOutcome.FIRED else safely("outcome ${armed.key}", null) { scheduler.outcome(armed) }
            if (!RingDecisions.isUnhandled(outcome)) {
                Log.i(TAG, "${armed.key} already handled ($outcome); no fallback")
                return
            }
            if (isRinging(armed)) return
            safely("post fallback ${armed.key}", Unit) { notifier.postFallbackAlarm(armed) }
            safely("arm guard ${armed.key}", Unit) { scheduler.armGuard(armed, 1, clock().plus(RingDecisions.guardBackoff(1))) }
            // The guard now owns the snooze occurrence; the planner must not arm it again.
            if (armed.isSnooze) safely("consume snooze", Unit) { consumeSnooze(armed) }
        } finally {
            safely("rearm", Unit) { scheduler.rearm("alarm fired, service refused") }
        }
    }

    /** The ring guard for [armed] fired; [attempt] counts the retries so far. Never throws. */
    suspend fun onGuard(armed: Armed, attempt: Int) {
        if (isRinging(armed)) return // the running service keeps re-arming the guard
        // Unreadable outcome: assume unhandled. Ringing twice beats not ringing.
        val outcome = safely("outcome ${armed.key}", FireOutcome.FIRED) { scheduler.outcome(armed) }
        when (RingDecisions.guard(outcome, armed.at, clock())) {
            GuardAction.RING -> {
                Log.w(TAG, "Guard: ${armed.key} still unhandled; ringing again (attempt $attempt)")
                if (!startRinging(armed, backup = false, guard = true)) {
                    val next = attempt + 1
                    safely("post fallback ${armed.key}", Unit) { notifier.postFallbackAlarm(armed) }
                    safely("re-arm guard ${armed.key}", Unit) {
                        scheduler.armGuard(armed, next, clock().plus(RingDecisions.guardBackoff(next)))
                    }
                }
            }
            GuardAction.GIVE_UP -> {
                Log.w(TAG, "Guard: ${armed.key} unhandled past the late window; reporting missed")
                safely("mark missed ${armed.key}", Unit) { scheduler.markMissed(armed) }
                safely("cancel guard", Unit) { scheduler.cancelGuard(armed) }
            }
            GuardAction.DONE -> safely("cancel guard", Unit) { scheduler.cancelGuard(armed) }
        }
    }

    /** The user dismissed the fallback alarm (notification action, swipe or fallback screen). */
    suspend fun onFallbackDismissed(armed: Armed) {
        notifier.cancelFallbackAlarm(armed)
        val recorded = safely("acknowledge fallback ${armed.key}", false) {
            scheduler.acknowledgeFallback(armed)
            true
        }
        // If the dismissal could not be recorded, the guard stays and rings again: better than losing it.
        if (recorded) safely("cancel guard", Unit) { scheduler.cancelGuard(armed) }
        if (isRinging(armed)) dismissRinging()
        safely("rearm", Unit) { scheduler.rearm("fallback dismissed") }
    }

    // ---- Service side ----

    /** Claims [armed] for a RING delivery and decides whether it rings. Never throws: on failure, it rings. */
    suspend fun decideDelivery(armed: Armed, guard: Boolean): Delivery {
        val claimed = safely("claim ${armed.key}", null) { withTimeoutOrNull(DB_TIMEOUT) { scheduler.claim(armed) } }
        val outcome = if (claimed == false && guard) safely("outcome ${armed.key}", null) { scheduler.outcome(armed) } else null
        return RingDecisions.delivery(claimed, guard, outcome)
    }

    /** [armed] started ringing: guard it, consume its snooze, reset auto-snoozes for a fresh alarm, arm the next one. */
    suspend fun onRingStarted(armed: Armed) {
        safely("arm guard ${armed.key}", Unit) { scheduler.armGuard(armed, 0, clock().plus(RingDecisions.GUARD_WHILE_RINGING)) }
        if (armed.isSnooze) {
            safely("consume snooze", Unit) { consumeSnooze(armed) }
        } else {
            safely("reset auto-snoozes", Unit) { state.setAutoSnoozeCount(0) }
        }
        safely("rearm", Unit) { scheduler.rearm("alarm fired") }
    }

    /** Keeps the guard of a still-ringing [armed] ahead. Returns false once another occurrence owns the guard. */
    suspend fun refreshGuard(armed: Armed): Boolean =
        safely("refresh guard ${armed.key}", true) { scheduler.refreshGuard(armed, clock().plus(RingDecisions.GUARD_WHILE_RINGING)) }

    /**
     * Persists a snooze (or, after [RingDecisions.MAX_AUTO_SNOOZES] auto-snoozes in a row, gives up as MISSED).
     * Throws if it could not be persisted: the caller must then keep ringing.
     */
    suspend fun snooze(armed: Armed, auto: Boolean) {
        val count = if (auto) state.autoSnoozeCount() + 1 else 0
        if (count > RingDecisions.MAX_AUTO_SNOOZES) {
            scheduler.markMissed(armed)
            state.setAutoSnoozeCount(0)
        } else {
            val minutes = safely("read snooze length", 9) { settings.current().snoozeMinutes }.coerceAtLeast(1)
            state.setAutoSnoozeCount(count)
            state.setSnooze(Armed.snooze(clock().plus(Duration.ofMinutes(minutes.toLong())), armed.label))
            scheduler.recordOutcome(armed, if (auto) FireOutcome.AUTO_SNOOZED else FireOutcome.SNOOZED)
            scheduler.rearm("snoozed")
        }
        safely("cancel guard", Unit) { scheduler.cancelGuard(armed) }
    }

    /** Persists a dismissal. Throws if it could not be persisted: the caller must then keep ringing. */
    suspend fun dismiss(armed: Armed) {
        scheduler.recordOutcome(armed, FireOutcome.DISMISSED)
        state.setAutoSnoozeCount(0)
        safely("cancel guard", Unit) { scheduler.cancelGuard(armed) }
    }

    private suspend fun consumeSnooze(armed: Armed) {
        if (state.snooze()?.key == armed.key) state.setSnooze(null)
    }

    private inline fun <T> safely(what: String, default: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$what failed", e)
        default
    }

    private companion object {
        val DB_TIMEOUT = 5.seconds
        const val TAG = "Reveille"
    }
}
