package com.replydev.reveille.alarm

import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RingThemeTest {
    private val at = Instant.parse("2026-11-03T06:55:00Z")
    private val overrides = mapOf(1L to RingTheme.MIDNIGHT, 2L to RingTheme.PAPER)

    private fun policyAlarm(policyId: Long) = Armed("P:$policyId:10:2026-11-03", at, "Work", listOf("P:$policyId:10:2026-11-03"))

    @Test
    fun policyAlarmUsesItsOverride() {
        assertEquals(RingTheme.MIDNIGHT, resolveTheme(policyAlarm(1), RingTheme.HORIZON, overrides))
    }

    @Test
    fun policyAlarmWithoutOverrideUsesTheDefault() {
        assertEquals(RingTheme.HORIZON, resolveTheme(policyAlarm(3), RingTheme.HORIZON, overrides))
    }

    @Test
    fun snoozeKeepsTheThemeOfThePolicyAlarmItSnoozes() {
        val snooze = Armed.snooze(at.plusSeconds(540), "Work", policyAlarm(2).sourceKeys)
        assertEquals(RingTheme.PAPER, resolveTheme(snooze, RingTheme.DAWN, overrides))
    }

    @Test
    fun snoozeWithoutSourcesUsesTheDefault() {
        assertEquals(RingTheme.HORIZON, resolveTheme(Armed.snooze(at, "Work"), RingTheme.HORIZON, overrides))
    }

    @Test
    fun oneOffUsesTheDefault() {
        assertEquals(RingTheme.HORIZON, resolveTheme(Armed("O:4", at, "Dentist", listOf("O:4")), RingTheme.HORIZON, overrides))
    }

    @Test
    fun testAlarmUsesTheDefault() {
        // The Health test alarm is an additive one-off.
        assertEquals(RingTheme.PAPER, resolveTheme(Armed("O:99", at, "Test alarm", listOf("O:99")), RingTheme.PAPER, overrides))
    }

    @Test
    fun oneOffMergedWithAPolicyAlarmUsesThePolicyOverride() {
        val merged = Armed("P:1:10:2026-11-03", at, "Work", listOf("P:1:10:2026-11-03", "O:4"))
        assertEquals(RingTheme.MIDNIGHT, resolveTheme(merged, RingTheme.DAWN, overrides))
    }

    @Test
    fun deletedPolicyWhoseOverrideWasDroppedUsesTheDefault() {
        assertEquals(RingTheme.DAWN, resolveTheme(policyAlarm(7), RingTheme.DAWN, overrides))
    }

    @Test
    fun unknownKeyUsesTheDefault() {
        assertEquals(RingTheme.HORIZON, resolveTheme(Armed("unknown", at, "Alarm"), RingTheme.HORIZON, overrides))
        assertEquals(RingTheme.HORIZON, resolveTheme(Armed("P:x:y:z", at, "Alarm", listOf("P:x:y:z")), RingTheme.HORIZON, overrides))
    }

    @Test
    fun ringPolicyIdReadsTheFirstPolicySource() {
        assertEquals(2L, ringPolicyId(Armed.snooze(at, "Work", policyAlarm(2).sourceKeys)))
        assertEquals(null, ringPolicyId(Armed("O:4", at, "Dentist", listOf("O:4"))))
    }

    @Test
    fun namesRoundTrip() {
        RingTheme.entries.forEach { assertEquals(it, RingTheme.fromName(it.name)) }
    }

    @Test
    fun unknownNamesMapToDawn() {
        assertEquals(RingTheme.DAWN, RingTheme.fromName(null))
        assertEquals(RingTheme.DAWN, RingTheme.fromName(""))
        assertEquals(RingTheme.DAWN, RingTheme.fromName("SEPIA"))
        assertEquals(RingTheme.DAWN, RingTheme.fromName("midnight"))
    }
}
