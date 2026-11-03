package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.replydev.reveille.container
import com.replydev.reveille.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms after reboot (including before the first unlock), app update, clock or time zone change, system language
 * change and exact-alarm permission changes, then refreshes the widget. Direct-boot aware: everything [AlarmScheduler.rearm] touches lives in device-protected storage,
 * and the watchdog and widget skip themselves while the user is locked.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported: anything else (e.g. an explicit intent from another app) is ignored.
        val reason = intent.action?.takeIf { it in ACTIONS } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                context.container.scheduler.rearm(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Re-arm failed ($reason)", e)
            } finally {
                try {
                    WatchdogWorker.schedule(context)
                    WidgetUpdater.update(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val TAG = "Reveille"
        val ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
