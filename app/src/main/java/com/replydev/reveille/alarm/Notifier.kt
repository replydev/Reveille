package com.replydev.reveille.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.replydev.reveille.MainActivity
import com.replydev.reveille.R
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.TimeText
import java.time.ZoneId

class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while an alarm rings. Keep this at high importance."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM_FALLBACK, "Fallback alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings by itself when the normal alarm cannot start. Keep this at high importance with sound."
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Missed alarms and problems", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun ringingNotification(ringing: Ringing): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            REQUEST_FULL_SCREEN,
            Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(ringing.title)
            .setContentText("Alarm for ${localTime(ringing.armed)}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Snooze", RingingService.actionIntent(context, RingingService.ACTION_SNOOZE))
            .addAction(0, "Dismiss", RingingService.actionIntent(context, RingingService.ACTION_DISMISS))
            .build()
    }

    /**
     * Last resort when [RingingService] cannot ring: a notification whose channel plays the alarm sound itself, repeated
     * ([Notification.FLAG_INSISTENT]) until the user opens the shade, taps or dismisses it. Dismissing (action or swipe)
     * marks the occurrence handled via [AlarmReceiver.ACTION_FALLBACK_DISMISS]. Re-posting while it is shown (retries)
     * does not restart the sound.
     */
    fun postFallbackAlarm(armed: Armed) {
        val id = fallbackId(armed)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_FALLBACK_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FALLBACK_DISMISS)
                .putExtra(AlarmScheduler.EXTRA_ARMED, armed.encode()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM_FALLBACK)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(armed.label)
            .setContentText("Alarm for ${localTime(armed)} (fallback)")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .addAction(0, "Dismiss", dismiss)
            .setOnlyAlertOnce(true)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        post(id, notification)
    }

    fun cancelFallbackAlarm(armed: Armed) = manager.cancel(fallbackId(armed))

    fun showMissed(armed: Armed) {
        val text = "${armed.label} at ${localTime(armed)} did not ring. Open Reveille → Reliability."
        post(MISSED_BASE_ID + (armed.at.epochSecond % 10_000).toInt(), alert("Missed alarm", text))
    }

    fun showSchedulingFailed(text: String) = post(SCHEDULING_FAILED_ID, alert("Alarms could not be scheduled", text))

    private fun alert(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(context, REQUEST_OPEN_APP, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun post(id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification $id", e)
        }
    }

    private fun fallbackId(armed: Armed): Int = FALLBACK_BASE_ID + Math.floorMod("${armed.key}@${armed.at}".hashCode(), 10_000)

    private fun localTime(armed: Armed): String = TimeText.time(armed.at.atZone(ZoneId.systemDefault()).toLocalTime())

    companion object {
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_ALARM_FALLBACK = "alarm_fallback"
        const val CHANNEL_ALERTS = "alerts"
        const val RINGING_ID = 1
        private const val SCHEDULING_FAILED_ID = 2
        private const val MISSED_BASE_ID = 1000
        private const val FALLBACK_BASE_ID = 20_000
        private const val REQUEST_FULL_SCREEN = 10
        private const val REQUEST_OPEN_APP = 11
        private const val REQUEST_FALLBACK_OPEN = 12
        private const val TAG = "Reveille"
    }
}
