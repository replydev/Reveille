package com.replydev.reveille.update

import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class InstallGuardTest {
    private val now = Instant.parse("2026-11-04T07:00:00Z")
    private fun alarm(inMinutes: Long, label: String = "Work") = Armed("P:1:1:2026-11-04", now.plus(Duration.ofMinutes(inMinutes)), label)

    @Test
    fun noAlarmIsAllowed() {
        assertEquals(GuardResult.Allowed, InstallGuard.check(armed = null, snooze = null, ringing = null, now = now))
    }

    @Test
    fun theWindowIsThirtyMinutesInclusive() {
        val atThirty = alarm(30)
        assertEquals(GuardResult.AlarmSoon(atThirty), InstallGuard.check(atThirty, null, null, now))
        val justAfter = Armed("O:1", now.plus(Duration.ofMinutes(30)).plusMillis(1), "Dentist")
        assertEquals(GuardResult.Allowed, InstallGuard.check(justAfter, null, null, now))
        assertEquals(GuardResult.Allowed, InstallGuard.check(alarm(31), null, null, now))
    }

    @Test
    fun anOverdueAlarmStillBlocks() {
        // Armed in the past: it is about to fire or be caught up, not safe to restart under.
        val overdue = alarm(-2)
        assertEquals(GuardResult.AlarmSoon(overdue), InstallGuard.check(overdue, null, null, now))
    }

    @Test
    fun aSnoozeDueSoonBlocksEvenWhenTheNextAlarmIsFarAway() {
        val snooze = Armed.snooze(now.plus(Duration.ofMinutes(9)), "Work")
        assertEquals(GuardResult.AlarmSoon(snooze), InstallGuard.check(alarm(600), snooze, null, now))
    }

    @Test
    fun theEarliestOfAlarmAndSnoozeIsReported() {
        val snooze = Armed.snooze(now.plus(Duration.ofMinutes(20)), "Work")
        val armed = alarm(10, "Gym")
        assertEquals(GuardResult.AlarmSoon(armed), InstallGuard.check(armed, snooze, null, now))
    }

    @Test
    fun ringingWinsOverEverything() {
        val ringing = alarm(0)
        assertEquals(GuardResult.Ringing(ringing), InstallGuard.check(alarm(5), null, ringing, now))
        assertEquals(GuardResult.Ringing(ringing), InstallGuard.check(null, null, ringing, now))
    }

    @Test
    fun theTodayCardYieldsToHealthAndToAlarms() {
        val release = TestReleases.release("v0.3.0")
        assertEquals(true, showUpdateNotice(release, failingChecks = 0, guard = GuardResult.Allowed))
        assertEquals(false, showUpdateNotice(null, 0, GuardResult.Allowed))
        assertEquals(false, showUpdateNotice(release, 2, GuardResult.Allowed))
        assertEquals(false, showUpdateNotice(release, -1, GuardResult.Allowed)) // checks could not run
        assertEquals(false, showUpdateNotice(release, null, GuardResult.Allowed)) // not loaded yet
        assertEquals(false, showUpdateNotice(release, 0, GuardResult.AlarmSoon(alarm(20))))
        assertEquals(false, showUpdateNotice(release, 0, GuardResult.Ringing(alarm(0))))
    }
}
