package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ModelTest {
    @Test
    fun `policy source key round-trips`() {
        val source = Source.PolicyRule(policyId = 3, ruleId = 7, date = LocalDate.of(2026, 11, 5))
        assertEquals("P:3:7:2026-11-05", source.key)
        assertEquals(source, Source.parse(source.key))
    }

    @Test
    fun `one-off source key round-trips`() {
        val source = Source.OneOffAlarm(12)
        assertEquals("O:12", source.key)
        assertEquals(source, Source.parse(source.key))
    }

    @Test
    fun `parse rejects malformed keys`() {
        assertNull(Source.parse(""))
        assertNull(Source.parse("P:x:1:2026-01-01"))
        assertNull(Source.parse("P:1:1:not-a-date"))
        assertNull(Source.parse("O:abc"))
        assertNull(Source.parse("S:123"))
    }

    @Test
    fun `armed alarm encodes and decodes`() {
        val armed = Armed(
            key = "P:1:2:2026-11-05",
            at = Instant.parse("2026-11-05T07:55:00Z"),
            label = "Work · Gym",
            sourceKeys = listOf("P:1:2:2026-11-05", "O:4"),
        )
        assertEquals(armed, Armed.decode(armed.encode()))
    }

    @Test
    fun `snooze has no sources and is recognised`() {
        val snooze = Armed.snooze(Instant.parse("2026-11-05T08:04:00Z"), "Work")
        assertTrue(snooze.isSnooze)
        assertEquals(snooze, Armed.decode(snooze.encode()))
    }

    @Test
    fun `decode rejects malformed values`() {
        assertNull(Armed.decode("nope"))
        assertNull(Armed.decode("a\u001Fnot-a-number\u001Fb\u001F"))
    }
}
