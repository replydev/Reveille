package com.replydev.reveille.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
                // Start the service first, before any database work: the foreground-service start exemption granted by
                // the alarm is short. The service claims the occurrence, rings and re-arms.
                if (RingingService.tryStart(context, armed, backup, guard = false)) return
                runAsync(context, "fire ${armed.key}") { it.onStartFailed(armed) }
            }
            AlarmScheduler.ACTION_GUARD -> {
                val attempt = intent.getIntExtra(AlarmScheduler.EXTRA_ATTEMPT, 0)
                if (RingingService.isRinging(armed)) return // the running service re-arms the guard itself
                runAsync(context, "guard ${armed.key}") { it.onGuard(armed, attempt) }
            }
            ACTION_FALLBACK_DISMISS -> runAsync(context, "fallback dismissed") { it.onFallbackDismissed(armed) }
        }
    }

    /** Runs [block] off the main thread and finishes the broadcast. Nothing here may crash the process. */
    private fun runAsync(context: Context, what: String, block: suspend (AlarmFiring) -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block(context.container.firing)
            } catch (e: Exception) {
                Log.e(TAG, "AlarmReceiver failed ($what)", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FALLBACK_DISMISS = "com.replydev.reveille.FALLBACK_DISMISS"
        private const val TAG = "Reveille"
    }
}
