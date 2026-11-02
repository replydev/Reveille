package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ArmPlannerTest {
    private val now = Instant.parse("2026-11-02T06:00:00Z")
    private fun armed(key: String, minutesFromNow: Long) = Armed(key, now.plus(Duration.ofMinutes(minutesFromNow)), key)

    @Test
    fun `arms the next occurrence`() {
        val next = armed("P:1:1:2026-11-02", 55)
        val decision = ArmPlanner.decide(previous = null, previousHandled = true, next = next, snooze = null, now = now)
        assertEquals(ArmDecision(next, next.at, null), decision)
    }

    @Test
    fun `arms nothing when nothing is scheduled`() {
        assertEquals(ArmDecision(null, null, null), ArmPlanner.decide(null, true, null, null, now))
    }

    @Test
    fun `earlier snooze wins over next occurrence`() {
        val next = armed("P:1:1:2026-11-02", 55)
        val snooze = armed("S:1", 9)
        assertEquals(snooze, ArmPlanner.decide(null, true, next, snooze, now).armed)
    }

    @Test
    fun `past snooze is ignored`() {
        val next = armed("P:1:1:2026-11-02", 55)
        assertEquals(next, ArmPlanner.decide(null, true, next, armed("S:1", -1), now).armed)
    }

    @Test
    fun `handled previous alarm is not re-armed`() {
        val next = armed("P:1:1:2026-11-03", 60 * 24)
        val decision = ArmPlanner.decide(armed("P:1:1:2026-11-02", -1), true, next, null, now)
        assertEquals(next, decision.armed)
        assertNull(decision.missed)
    }

    @Test
    fun `future previous alarm is simply replaced`() {
        val next = armed("P:1:1:2026-11-02", 55)
        val decision = ArmPlanner.decide(armed("O:9", 30), false, next, null, now)
        assertEquals(next, decision.armed)
        assertNull(decision.missed)
    }

    @Test
    fun `unfired alarm within the late window rings now`() {
        val previous = armed("P:1:1:2026-11-02", -5)
        val decision = ArmPlanner.decide(previous, false, armed("P:1:1:2026-11-03", 60 * 24), null, now)
        assertEquals(previous, decision.armed)
        assertEquals(now.plus(ArmPlanner.CATCH_UP_DELAY), decision.triggerAt)
        assertNull(decision.missed)
    }

    @Test
    fun `unfired alarm beyond the late window is reported missed`() {
        val previous = armed("P:1:1:2026-11-02", -31)
        val next = armed("P:1:1:2026-11-03", 60 * 24)
        val decision = ArmPlanner.decide(previous, false, next, null, now)
        assertEquals(previous, decision.missed)
        assertEquals(next, decision.armed)
        assertEquals(next.at, decision.triggerAt)
    }
}
