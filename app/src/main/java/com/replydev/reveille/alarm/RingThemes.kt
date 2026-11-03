package com.replydev.reveille.alarm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.replydev.reveille.ui.theme.Geist
import com.replydev.reveille.ui.theme.GeistMono

/**
 * Colours that only the ringing themes use (docs/design/ringing-themes.md §3). They stay out of the app's palette:
 * everything else on the ringing screen comes from [com.replydev.reveille.ui.theme.ReveilleTheme].
 */
object RingColors {
    val MidnightBg = Color(0xFF000000)

    /** Overline, backup line and footnote: secondary text only (4.6:1 on black). */
    val MidnightMuted = Color(0xFF8A7550)
    val MidnightText = Color(0xFFE8D3AE)

    val PolicyInk = Color(0xFF0B1A33)

    val HorizonSky = Color(0xFF10142A)

    /** Top to bottom, with their heights in dp: flat bands, no gradient. */
    val HorizonBands = listOf(
        Color(0xFF1C1F45) to 80,
        Color(0xFF352A5C) to 60,
        Color(0xFF63365E) to 60,
        Color(0xFFA84A5E) to 50,
        Color(0xFFE57B4E) to 50,
    )
    val HorizonDate = Color(0xFFC9CDE8)
    val HorizonLabel = Color(0xFFE4E6F5)

    val PaperBg = Color(0xFFF4F1EA)
    val PaperInk = Color(0xFF14130F)
    val PaperSecondary = Color(0xFF5E594E)
    val PaperHairline = Color(0xFFC9C3B5)
    val PaperTrack = Color(0xFFE2DDD1)
    val PaperAccent = Color(0xFFE8862A)
}

private fun ring(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0, family: FontFamily = Geist) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum",
)

/** Type that only the ringing themes use; Dawn keeps the app's own roles (displayXl, ringOverline, ringButton). */
object RingType {
    val midnightOverline = ring(13, FontWeight.Medium, 0.16)
    val midnightClock = ring(132, FontWeight.ExtraLight, -0.05)
    val midnightLabel = ring(18, FontWeight.Medium)
    val midnightBackup = ring(14)
    val midnightDismiss = ring(20, FontWeight.SemiBold)
    val midnightSnooze = ring(20, FontWeight.Medium)

    val policyName = ring(44, FontWeight.SemiBold, -0.03)
    val policyDays = ring(13, FontWeight.SemiBold)
    val policyLine = ring(15)
    val policyClock = ring(148, FontWeight.ExtraLight, -0.06)
    val policyButton = ring(19, FontWeight.SemiBold)
    val policyButtonDetail = ring(14, FontWeight.Medium)

    val horizonClock = ring(120, FontWeight.ExtraLight, -0.05)
    val horizonLabel = ring(17, FontWeight.Medium)
    val horizonSnooze = ring(18, FontWeight.Medium)

    val paperMast = ring(14, FontWeight.Bold, 0.14)
    val paperDate = ring(14, FontWeight.Medium)
    val paperClock = ring(156, FontWeight.Bold, -0.07)
    val paperCellTitle = ring(12, FontWeight.SemiBold, 0.12)
    val paperCellValue = ring(18, FontWeight.SemiBold)
    val paperCellMono = ring(18, FontWeight.Medium, family = GeistMono)
    val paperButton = ring(20, FontWeight.SemiBold)
    val paperButtonDetail = ring(16, FontWeight.Medium, family = GeistMono)

    /** "AM" / "PM" after a themed clock on a 12-hour phone. */
    fun period(weight: FontWeight) = ring(17, weight)
}

/** Whether the theme's background is dark, so the system bars get light icons. */
val RingTheme.darkBackground: Boolean
    get() = this == RingTheme.MIDNIGHT || this == RingTheme.HORIZON

/** Window brightness while ringing: Midnight dims its own window; null leaves the user's brightness alone. */
val RingTheme.screenBrightness: Float?
    get() = if (this == RingTheme.MIDNIGHT) 0.15f else null

/** The semantics tag on a theme's root, so tests can tell which theme rendered. */
fun ringThemeTag(theme: RingTheme): String = "ring-theme-${theme.name}"
