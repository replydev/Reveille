package com.replydev.reveille.alarm

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.R
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 33, 35])
class LocalizedAlarmTextTest {
    /** The alarm path runs before the first unlock: its text must resolve from the device-protected context. */
    private val context: Context =
        ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
    private val armed = Armed("O:1", Instant.parse("2026-11-03T06:30:00Z"), "Gym")

    @Test
    fun missedAlarmTextResolvesInDirectBoot() {
        val notifier = Notifier(context).apply { createChannels() }
        notifier.showMissed(armed)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(context.getString(R.string.notif_missed_title), posted.extras.getString("android.title"))
        assertTrue(posted.extras.getCharSequence("android.text").toString().startsWith("Gym"))
    }

    @Test
    fun ringingTitleMarksBackup() {
        assertEquals("Gym", Ringing(armed, backup = false).title(context))
        assertEquals(context.getString(R.string.ringing_backup_title, "Gym"), Ringing(armed, backup = true).title(context))
    }

    @Test
    @Config(qualifiers = "it")
    fun channelsAreNamedInTheCurrentLanguage() {
        Notifier(context).createChannels()
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(Notifier.CHANNEL_ALARM)
        assertEquals(context.getString(R.string.notif_channel_alarm_name), channel.name)
    }
}
