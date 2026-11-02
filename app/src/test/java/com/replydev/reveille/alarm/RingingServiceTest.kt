package com.replydev.reveille.alarm

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.container
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Drives the real service (and the app's real container) through Robolectric's ServiceController. */
@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class RingingServiceTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<RingingService>
    private var startId = 0
    private val armed = Armed("O:1", Instant.now().truncatedTo(ChronoUnit.MILLIS), "Dentist")

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(RingingService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun send(intent: Intent) {
        controller.withIntent(intent).startCommand(0, ++startId)
    }

    private fun ring(armed: Armed, guard: Boolean = false) = send(RingingService.ringIntent(app, armed, backup = false, guard = guard))

    private fun action(action: String) = send(Intent(app, RingingService::class.java).setAction(action))

    /** Runs main-looper work and waits for Room/DataStore threads until [condition] holds. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun outcome(armed: Armed) = runBlocking { app.container.scheduler.outcome(armed) }

    private fun stopped() = shadowOf(controller.get()).isStoppedBySelf

    @Test
    fun ringsGoesForegroundAndGuardsTheOccurrence() {
        ring(armed)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        awaitUntil { RingingService.ringing.value != null && runBlocking { app.container.schedulerState.guard() } != null }
        assertEquals(armed.key, RingingService.ringing.value?.armed?.key)
        assertEquals(FireOutcome.FIRED, outcome(armed))
    }

    @Test
    fun snoozeIsPersistedBeforeTheServiceStops() {
        ring(armed)
        awaitUntil { RingingService.ringing.value != null && runBlocking { app.container.schedulerState.guard() } != null }
        action(RingingService.ACTION_SNOOZE)
        awaitUntil { RingingService.ringing.value == null }
        // By the time ringing stopped, everything was already persisted.
        assertEquals(FireOutcome.SNOOZED, outcome(armed))
        val snooze = runBlocking { app.container.schedulerState.snooze() }
        assertNotNull(snooze)
        assertEquals(snooze?.key, runBlocking { app.container.schedulerState.armed() }?.key)
        assertNull(runBlocking { app.container.schedulerState.guard() })
        assertTrue(stopped())
    }

    @Test
    fun duplicateDeliveryDoesNotRingAgain() {
        ring(armed)
        awaitUntil { RingingService.ringing.value != null }
        action(RingingService.ACTION_DISMISS)
        awaitUntil { RingingService.ringing.value == null }
        assertEquals(FireOutcome.DISMISSED, outcome(armed))

        ring(armed) // backup alarm, two minutes later
        ring(armed, guard = true) // a stale guard
        awaitUntil { stopped() }
        assertNull(RingingService.ringing.value)
        assertEquals(FireOutcome.DISMISSED, outcome(armed))
    }

    @Test
    fun backupOfTheRingingAlarmKeepsRingingIt() {
        ring(armed)
        awaitUntil { RingingService.ringing.value != null }
        val ringing = RingingService.ringing.value
        ring(armed)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(RingingService.ringing.value === ringing)
    }

    @Test
    fun newerAlarmSupersedesTheRingingOne() {
        ring(armed)
        awaitUntil { RingingService.ringing.value != null && runBlocking { app.container.schedulerState.guard() } != null }
        val newer = Armed("O:2", armed.at.plusSeconds(60), "Gym")
        ring(newer)
        awaitUntil { RingingService.ringing.value?.armed?.key == newer.key && runBlocking { app.container.schedulerState.guard() }?.key == newer.key }
        assertEquals(FireOutcome.SUPERSEDED, outcome(armed))
        assertEquals(FireOutcome.FIRED, outcome(newer))
    }

    @Test
    fun actionWithNothingRingingJustStops() {
        action(RingingService.ACTION_DISMISS)
        assertTrue(stopped())
    }
}
