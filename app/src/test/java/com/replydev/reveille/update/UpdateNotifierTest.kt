package com.replydev.reveille.update

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.MainActivity
import com.replydev.reveille.R
import com.replydev.reveille.alarm.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class UpdateNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = UpdateNotifier(context)
    private val release = TestReleases.release(
        "v0.3.0",
        notes = "## Features\n\n- feat(ui): replace the launcher icon with the Daybreak mark (#7)\n",
    )

    @Test
    fun postsAQuietNotificationOnItsOwnChannel() {
        notifier.show(release)
        val channel = manager.getNotificationChannel(UpdateNotifier.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(channel.id !in setOf(Notifier.CHANNEL_ALARM, Notifier.CHANNEL_ALARM_FALLBACK, Notifier.CHANNEL_ALERTS))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(UpdateNotifier.CHANNEL, posted.channelId)
        assertEquals("Reveille v0.3.0 is available", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Replace the launcher icon with the Daybreak mark", posted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(R.drawable.ic_mark, posted.smallIcon.resId)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun tappingOpensTheReleaseSheet() {
        notifier.show(release)
        val target = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, target.component?.className)
        assertEquals(MainActivity.OPEN_UPDATE, target.getStringExtra(MainActivity.EXTRA_OPEN))
    }

    @Test
    fun oneNotificationPerVersionAndCancellable() {
        notifier.show(release)
        notifier.show(TestReleases.release("v0.4.0"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Reveille v0.4.0 is available", shadowOf(manager).allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        notifier.cancel()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
