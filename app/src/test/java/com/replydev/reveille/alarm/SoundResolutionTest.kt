package com.replydev.reveille.alarm

import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SoundResolutionTest {
    private val at = Instant.parse("2026-11-12T06:30:00Z")
    private val global = SoundRef.SystemTone("content://global")
    private val forPolicy = SoundRef.Imported("p")
    private val forOneOff = SoundRef.Imported("o")
    private val settings = RingSettings(sound = global, policySounds = mapOf(1L to forPolicy), oneOffSounds = mapOf(5L to forOneOff))

    private fun armed(key: String, vararg sources: String) = Armed(key, at, "x", sources.toList())

    @Test
    fun policyAlarmUsesItsOverride() = assertEquals(forPolicy, resolveSound(armed("P:1:3:2026-11-12", "P:1:3:2026-11-12"), settings))

    @Test
    fun policyWithoutOverrideUsesTheGlobalSound() = assertEquals(global, resolveSound(armed("P:2:3:2026-11-12"), settings))

    @Test
    fun oneOffUsesItsOverride() = assertEquals(forOneOff, resolveSound(armed("O:5", "O:5"), settings))

    @Test
    fun oneOffWithoutOverrideUsesTheGlobalSound() = assertEquals(global, resolveSound(armed("O:6"), settings))

    @Test
    fun mergedOccurrencePrefersTheOneOff() =
        assertEquals(forOneOff, resolveSound(armed("P:1:3:2026-11-12", "P:1:3:2026-11-12", "O:5"), settings))

    @Test
    fun snoozesKeepTheirAlarmsSound() {
        assertEquals(forPolicy, resolveSound(Armed.snooze(at, "x", listOf("P:1:3:2026-11-12")), settings))
        assertEquals(forOneOff, resolveSound(Armed.snooze(at, "x", listOf("O:5")), settings))
    }

    @Test
    fun testAlarmAndUnknownKeysUseTheGlobalSound() {
        assertEquals(global, resolveSound(armed("T:1"), settings))
        assertEquals(global, resolveSound(armed("unknown"), settings))
    }

    @Test
    fun candidatesAlwaysEndWithSystemDefaultThenBundled() {
        val path = { id: String -> "/de/files/sounds/$id" }
        assertEquals(
            listOf(SoundSource.File("/de/files/sounds/p"), SoundSource.SystemDefault, SoundSource.Bundled),
            soundCandidates(forPolicy, path),
        )
        assertEquals(
            listOf(SoundSource.Uri("content://global"), SoundSource.SystemDefault, SoundSource.Bundled),
            soundCandidates(global, path),
        )
        assertEquals(listOf(SoundSource.SystemDefault, SoundSource.Bundled), soundCandidates(SoundRef.SystemDefault, path))
    }

    @Test
    fun prepareFirstFallsThroughFailures() {
        val failed = mutableListOf<SoundSource>()
        val used = prepareFirst(
            listOf(SoundSource.File("/missing"), SoundSource.SystemDefault, SoundSource.Bundled),
            attempt = { if (it !is SoundSource.Bundled) throw IOException("no") },
            onFailure = { source, _ -> failed += source },
        )
        assertEquals(SoundSource.Bundled, used)
        assertEquals(listOf(SoundSource.File("/missing"), SoundSource.SystemDefault), failed)
    }

    @Test
    fun prepareFirstThrowsTheLastFailureWhenNothingWorks() {
        assertThrows(IOException::class.java) {
            prepareFirst(listOf(SoundSource.SystemDefault, SoundSource.Bundled), attempt = { throw IOException("no") }, onFailure = { _, _ -> })
        }
    }
}
