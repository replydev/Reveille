package com.replydev.reveille.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.replydev.reveille.MainActivity
import com.replydev.reveille.R

/**
 * The "new release" notification, on its own LOW-importance channel. The channel is created here, on first use, rather
 * than in `ReveilleApp`, so that a process started to ring an alarm never runs update code.
 */
class UpdateNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(release: Release) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.update_channel_description)
            },
        )
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = ReleaseNotes.parse(release.notes).headline ?: context.getString(R.string.update_notification_text)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(context.getString(R.string.update_notification_title, release.versionName))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("Reveille", "Cannot post the update notification", e)
        }
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val CHANNEL = "updates"

        /** A single id: a newer release replaces the older one's notification. Clear of the alarm notifier's ids. */
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_OPEN = 30
    }
}
