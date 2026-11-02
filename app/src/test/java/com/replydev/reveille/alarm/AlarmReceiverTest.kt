package com.replydev.reveille.alarm

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class AlarmReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val armed = Armed("O:1", Instant.parse("2026-11-02T06:30:00Z"), "Dentist")

    private fun fire(backup: Boolean) = Intent(app, AlarmReceiver::class.java)
        .setAction(AlarmScheduler.ACTION_FIRE)
        .putExtra(AlarmScheduler.EXTRA_ARMED, armed.encode())
        .putExtra(AlarmScheduler.EXTRA_BACKUP, backup)

    @Test
    fun fireStartsTheRingingServiceSynchronously() {
        AlarmReceiver().onReceive(app, fire(backup = true))
        // Started before onReceive returned, and without going async (no DB work happened in the receiver).
        val started = shadowOf(app).nextStartedService
        assertEquals(RingingService.ACTION_RING, started.action)
        assertEquals(armed, Armed.decode(started.getStringExtra(AlarmScheduler.EXTRA_ARMED)!!))
        assertTrue(started.getBooleanExtra("backup", false))
        assertEquals(false, started.getBooleanExtra("guard", true))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun unreadableIntentIsIgnored() {
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_FIRE))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun fireHoldsAWakeLockUntilTheServiceTakesOver() {
        FiringWakeLock.release()
        AlarmReceiver().onReceive(app, fire(backup = false))
        assertTrue(FiringWakeLock.isHeld) // the (not yet created) service releases it once it holds its own
        FiringWakeLock.release()
    }
}
