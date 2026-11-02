package com.replydev.reveille.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.replydev.reveille.AppContainer
import com.replydev.reveille.container
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val armed = intent.getStringExtra(AlarmScheduler.EXTRA_ARMED)?.let(Armed::decode) ?: return
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val backup = intent.getBooleanExtra(AlarmScheduler.EXTRA_BACKUP, false)
                runAsync(context, if (backup) "backup fired" else "alarm fired") { deliver(context, it, armed, backup) }
            }
            ACTION_FALLBACK_DISMISS -> runAsync(context, "fallback dismissed") { c ->
                c.scheduler.acknowledgeFallback(armed)
                c.notifier.cancelFallbackAlarm(armed)
                if (RingingService.ringing.value?.armed?.key == armed.key) RingingService.send(context, RingingService.ACTION_DISMISS)
            }
        }
    }

    /** Runs [block], then always re-arms, then finishes the broadcast. Nothing here may crash the process. */
    private fun runAsync(context: Context, rearmReason: String, block: suspend (AppContainer) -> Unit) {
        val container = context.container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block(container)
            } catch (e: Exception) {
                Log.e(TAG, "AlarmReceiver failed ($rearmReason)", e)
            } finally {
                try {
                    container.scheduler.rearm(rearmReason)
                } catch (e: Exception) {
                    Log.e(TAG, "rearm($rearmReason) failed", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private suspend fun deliver(context: Context, c: AppContainer, armed: Armed, backup: Boolean) {
        val claimed = try {
            c.scheduler.claim(armed)
        } catch (e: Exception) {
            // Ringing twice beats not ringing: without the fire log we cannot dedupe, so ring.
            Log.e(TAG, "Cannot claim ${armed.key}; ringing anyway", e)
            true
        }
        if (!claimed) return
        try {
            RingingService.start(context, armed, backup)
        } catch (e: Exception) {
            Log.e(TAG, "RingingService did not start for ${armed.key}; posting fallback alarm", e)
            c.notifier.postFallbackAlarm(armed)
            // Release the claim so the re-arm in finally catches the occurrence up and retries the service.
            try {
                c.scheduler.unclaim(armed)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot release claim on ${armed.key}", e2)
            }
            return
        }
        // Only now is the snooze consumed: on failure it stays, so the planner catches it up like any overdue alarm.
        if (armed.isSnooze) c.schedulerState.setSnooze(null)
    }

    companion object {
        const val ACTION_FALLBACK_DISMISS = "com.replydev.reveille.FALLBACK_DISMISS"
        private const val TAG = "Reveille"
    }
}
