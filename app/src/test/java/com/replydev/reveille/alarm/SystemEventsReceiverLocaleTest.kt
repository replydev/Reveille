package com.replydev.reveille.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.OneOffMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalTime

/**
 * A system language change re-arms (so notification text and the widget follow the new language) and refreshes the
 * widget. Same pattern as [PackageReplacedTest].
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

    private suspend fun awaitArmed() {
        repeat(100) {
            if (app.container.schedulerState.armed() != null && alarms.scheduledAlarms.isNotEmpty()) return
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
