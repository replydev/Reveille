package com.replydev.reveille.ui.theme

import androidx.compose.ui.graphics.Color
import com.replydev.reveille.engine.Source
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PolicyColorsTest {
    private val blue = Color(0xFF8AB4FF)
    private val mint = Color(0xFF7EE0C3)
    private val pink = Color(0xFFF2A0C8)
    private val lavender = Color(0xFFC9B6FF)

    @Test
    fun `colour cycles through the palette by id`() {
        assertEquals(blue, policyColor(4))
        assertEquals(mint, policyColor(1))
        assertEquals(pink, policyColor(2))
        assertEquals(lavender, policyColor(3))
        assertEquals(mint, policyColor(5))
    }

    @Test
    fun `colour is stable for the same id`() {
        assertEquals(policyColor(42), policyColor(42))
    }

    @Test
    fun `tint matches the colour's slot`() {
        assertEquals(Color(0xFF1A2233), policyTint(0))
        assertEquals(Color(0xFF15282A), policyTint(1))
        assertEquals(Color(0xFF2A1823), policyTint(2))
        assertEquals(Color(0xFF211C33), policyTint(3))
    }

    @Test
    fun `negative ids still map into the palette`() {
        assertEquals(lavender, policyColor(-1))
    }

    @Test
    fun `occurrence colour is its first policy, or the accent for a one-off`() {
        val day = LocalDate.of(2026, 11, 3)
        assertEquals(pink, sourceColor(listOf(Source.PolicyRule(2, 7, day), Source.OneOffAlarm(9))))
        assertEquals(Palette.Accent, sourceColor(listOf(Source.OneOffAlarm(9))))
        assertEquals(Palette.Accent, sourceColor(emptyList()))
    }

    @Test
    fun `armed colour reads the source keys and falls back to the accent`() {
        assertEquals(lavender, sourceKeyColor(listOf("P:3:1:2026-11-03")))
        assertEquals(Palette.Accent, sourceKeyColor(listOf("O:4")))
        assertEquals(Palette.Accent, sourceKeyColor(emptyList()))
    }
}
