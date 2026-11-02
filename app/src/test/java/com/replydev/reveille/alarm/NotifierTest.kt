package com.replydev.reveille.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class NotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: Notifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        notifier = Notifier(context)
        notifier.createChannels()
    }

    @Test
    fun fallbackChannelRingsByItselfAsAnAlarm() {
        val channel = manager.getNotificationChannel(Notifier.CHANNEL_ALARM_FALLBACK)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        // The service-driven channel stays silent: the service plays the sound.
        assertNull(manager.getNotificationChannel(Notifier.CHANNEL_ALARM).sound)
    }

    @Test
    fun fallbackAlarmIsInsistentOnTheFallbackChannelAndCancellable() {
        val armed = Armed("O:1", Instant.parse("2026-11-02T06:30:00Z"), "Dentist")
        notifier.postFallbackAlarm(armed)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(Notifier.CHANNEL_ALARM_FALLBACK, posted.channelId)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull(posted.fullScreenIntent)
        assertNotNull(posted.deleteIntent)

        notifier.cancelFallbackAlarm(armed)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
