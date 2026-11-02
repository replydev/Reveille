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
import org.robolectric.shadows.ShadowLog
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
        // The bundled sound: deterministic and readable before the first unlock.
        assertEquals("android.resource://${context.packageName}/raw/fallback_alarm", channel.sound.toString())
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
        // Opens the directBootAware RingingActivity in fallback mode, not MainActivity.
        val target = shadowOf(posted.fullScreenIntent).savedIntent
        assertEquals(RingingActivity::class.java.name, target.component?.className)
        assertEquals(armed, Armed.decode(target.getStringExtra("fallback_armed")!!))
        assertNotNull(posted.deleteIntent)

        notifier.cancelFallbackAlarm(armed)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun droppedNotificationIsLogged() {
        shadowOf(manager).setNotificationsEnabled(false)
        notifier.postFallbackAlarm(Armed("O:1", Instant.parse("2026-11-02T06:30:00Z"), "Dentist"))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("Reveille").any { it.msg.contains("dropped notification") })
    }
}
