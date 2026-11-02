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
 * An in-app update replaces the APK, which kills the process and can drop our pending alarms. Until the user opens
 * the app again, `MY_PACKAGE_REPLACED` is the only thing that re-arms (spec: updates §1.4).
 */
@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class PackageReplacedTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = app.container.database.close()

    @Test
    fun myPackageReplacedReArmsTheNextAlarm() = runBlocking {
        // Written straight to the database: nothing is armed yet, as after a replace that dropped our alarms.
        app.container.database.scheduleDao().saveOneOff(
            OneOffEntity(date = LocalDate.now().plusDays(1), time = LocalTime.of(6, 30), label = "Flight", mode = OneOffMode.ADDITIVE),
        )
        assertNull(app.container.schedulerState.armed())
        assertTrue(alarms.scheduledAlarms.isEmpty())

        app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        awaitArmed()

        val armed = app.container.schedulerState.armed()!!
        assertEquals("Flight", armed.label)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == armed.at.toEpochMilli() })
    }

    @Test
    fun otherPackagesBeingReplacedAreIgnored() = runBlocking {
        SystemEventsReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(app.container.schedulerState.armed())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    private suspend fun awaitArmed() {
        repeat(100) {
            if (app.container.schedulerState.armed() != null && alarms.scheduledAlarms.isNotEmpty()) return
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
