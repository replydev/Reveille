package com.replydev.reveille.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.replydev.reveille.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms after reboot (including before the first unlock), app update, clock or time zone change, and exact-alarm
 * permission changes. Direct-boot aware: everything [AlarmScheduler.rearm] touches lives in device-protected storage,
 * and the watchdog and widget skip themselves while the user is locked.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: "system event"
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                context.container.scheduler.rearm(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Re-arm failed ($reason)", e)
            } finally {
                try {
                    WatchdogWorker.schedule(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val TAG = "Reveille"
    }
}
