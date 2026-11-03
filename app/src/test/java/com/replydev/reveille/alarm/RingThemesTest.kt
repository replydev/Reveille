package com.replydev.reveille.alarm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.replydev.reveille.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contrast checks of docs/design/ringing-themes.md §1.4. */
class RingThemesTest {
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return light / dark
    }

    private fun Color.over(background: Color): Color = Color(
        red = red * alpha + background.red * (1 - alpha),
        green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha),
    )

    @Test
    fun policyInkReadsOnEveryPolicyColour() {
        Palette.PolicyColors.forEach { policy ->
            assertTrue("ink on $policy", contrast(RingColors.PolicyInk, policy) >= 4.5)
            // The date and "Next" lines are 15 sp at 80 % ink.
            assertTrue("80 % ink on $policy", contrast(RingColors.PolicyInk.copy(alpha = 0.8f).over(policy), policy) >= 4.5)
        }
        // Policy colour without a policy falls back to the accent.
        assertTrue(contrast(Palette.OnAccent, Palette.Accent) >= 4.5)
    }

    @Test
    fun midnightTextReadsOnBlack() {
        assertTrue(contrast(RingColors.MidnightMuted, RingColors.MidnightBg) >= 4.5)
        assertTrue(contrast(RingColors.MidnightText, RingColors.MidnightBg) >= 4.5)
        assertTrue(contrast(Palette.Accent, Palette.AccentContainer) >= 4.5)
    }

    @Test
    fun horizonAndPaperTextReads() {
        assertTrue(contrast(RingColors.HorizonDate, RingColors.HorizonSky) >= 4.5)
        assertTrue(contrast(RingColors.HorizonLabel, RingColors.HorizonSky) >= 4.5)
        assertTrue(contrast(RingColors.PaperSecondary, RingColors.PaperBg) >= 4.5)
        assertTrue(contrast(RingColors.PaperInk, RingColors.PaperBg) >= 4.5)
        assertTrue(contrast(RingColors.PaperBg, RingColors.PaperInk) >= 4.5)
    }
}
