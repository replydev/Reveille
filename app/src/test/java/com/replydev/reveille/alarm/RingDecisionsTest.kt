package com.replydev.reveille.alarm

import com.replydev.reveille.data.FireOutcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RingDecisionsTest {
    private val at = Instant.parse("2026-11-02T06:30:00Z")
    private val terminal = FireOutcome.entries - FireOutcome.FIRED

    @Test
    fun firstDeliveryRingsAndLaterOnesAreDuplicates() {
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = true, retry = false, outcome = null))
        assertEquals(Delivery.DUPLICATE, RingDecisions.delivery(claimed = false, retry = false, outcome = FireOutcome.FIRED))
        assertEquals(Delivery.DUPLICATE, RingDecisions.delivery(claimed = false, retry = false, outcome = null))
    }

    @Test
    fun unknownClaimRings() {
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = null, retry = false, outcome = null))
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = null, retry = true, outcome = FireOutcome.DISMISSED))
    }

    @Test
    fun guardReRingRingsOnlyWhileUnhandled() {
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = false, retry = true, outcome = FireOutcome.FIRED))
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = false, retry = true, outcome = null))
        terminal.forEach { assertEquals(it.name, Delivery.DUPLICATE, RingDecisions.delivery(claimed = false, retry = true, outcome = it)) }
    }

    @Test
    fun backupRingsLikeAGuardWhileUnhandled() {
        // retry = guard || backup: a claimed occurrence nobody protected still rings on its backup.
        assertEquals(Delivery.RING, RingDecisions.delivery(claimed = false, retry = true, outcome = FireOutcome.FIRED))
        assertEquals(Delivery.DUPLICATE, RingDecisions.delivery(claimed = false, retry = true, outcome = FireOutcome.DISMISSED))
    }

    @Test
    fun guardRingsInsideTheLateWindowAndGivesUpAfter() {
        assertEquals(GuardAction.RING, RingDecisions.guard(FireOutcome.FIRED, at, at.plus(Duration.ofMinutes(30))))
        assertEquals(GuardAction.RING, RingDecisions.guard(null, at, at.plusSeconds(10)))
        assertEquals(GuardAction.GIVE_UP, RingDecisions.guard(FireOutcome.FIRED, at, at.plus(Duration.ofMinutes(30)).plusMillis(1)))
        terminal.forEach { assertEquals(it.name, GuardAction.DONE, RingDecisions.guard(it, at, at.plusSeconds(10))) }
        terminal.forEach { assertEquals(it.name, GuardAction.DONE, RingDecisions.guard(it, at, at.plus(Duration.ofHours(1)))) }
    }

    @Test
    fun guardBacksOffTo60Seconds() {
        assertEquals(listOf(10L, 30L, 60L, 60L, 60L), (1..5).map { RingDecisions.guardBackoff(it).seconds })
    }

    @Test
    fun ringDurationIsCappedBelowTheWakeLock() {
        assertEquals(30, RingDecisions.ringMinutes(120))
        assertEquals(10, RingDecisions.ringMinutes(10))
        assertEquals(1, RingDecisions.ringMinutes(0))
    }
}
