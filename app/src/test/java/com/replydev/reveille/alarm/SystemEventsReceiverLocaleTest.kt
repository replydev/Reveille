package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.OneOffMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A system language change re-arms (so notification text and the widget follow the new language), renames the
 * notification channels and refreshes the widget. Same pattern as [PackageReplacedTest].
 */
@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class SystemEventsReceiverLocaleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = app.container.database.close()

    @Test
    fun localeChangedReArmsTheNextAlarm() = runBlocking {
        app.container.database.scheduleDao().saveOneOff(
            OneOffEntity(date = LocalDate.now().plusDays(1), time = LocalTime.of(6, 30), label = "Flight", mode = OneOffMode.ADDITIVE),
        )
        assertNull(app.container.schedulerState.armed())
        assertTrue(alarms.scheduledAlarms.isEmpty())

        app.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        awaitArmed()

        val armed = app.container.schedulerState.armed()!!
        assertEquals("Flight", armed.label)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == armed.at.toEpochMilli() })
    }

    @Test
    fun localeChangedRenamesTheNotificationChannels() = runBlocking {
        // The app process started in English, so the channels were created with English names.
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals("Ringing alarms", manager.getNotificationChannel(Notifier.CHANNEL_ALARM).name)

        RuntimeEnvironment.setQualifiers("it")
        app.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        val expected = app.getString(R.string.notif_channel_alarm_name)
        assertEquals("Sveglie che suonano", expected)
        awaitUntil { manager.getNotificationChannel(Notifier.CHANNEL_ALARM).name == expected }
        assertEquals(expected, manager.getNotificationChannel(Notifier.CHANNEL_ALARM).name)
    }

    @Test
    @Config(qualifiers = "it")
    fun localeChangedRelabelsAnUnlabeledArmedOneOff() = runBlocking {
        val date = LocalDate.now().plusDays(1)
        val time = LocalTime.of(6, 30)
        val id = app.container.database.scheduleDao().saveOneOff(
            OneOffEntity(date = date, time = time, label = null, mode = OneOffMode.ADDITIVE),
        )
        // Armed while the app was still in English.
        val at = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()
        app.container.schedulerState.setArmed(Armed("O:$id", at, "Alarm"))

        app.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil { app.container.schedulerState.armed()?.label == "Sveglia" }

        val armed = app.container.schedulerState.armed()!!
        assertEquals("O:$id", armed.key)
        assertEquals(at, armed.at)
        assertEquals("Sveglia", armed.label)
    }

    private suspend fun awaitArmed() = awaitUntil { app.container.schedulerState.armed() != null && alarms.scheduledAlarms.isNotEmpty() }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
