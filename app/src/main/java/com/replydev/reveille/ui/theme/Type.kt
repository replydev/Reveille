package com.replydev.reveille.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.replydev.reveille.R

/** Bundled, not downloadable: the ringing screen must render in Direct Boot with no network. */
val Geist = FontFamily(
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

/** Only for technical strings: "backup 07:57", log timestamps, the version. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/** Every numeral is tabular, so times line up and a ticking clock does not jitter. */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0, family: FontFamily = Geist) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum",
)

/** The type roles of docs/design/README.md §1. */
@Immutable
class ReveilleType(
    val displayXl: TextStyle = style(124, FontWeight.Light, -0.05).copy(lineHeight = 1.0.em),
    val displayL: TextStyle = style(84, FontWeight.Light, -0.04).copy(lineHeight = 0.9.em),
    val displayM: TextStyle = style(72, FontWeight.Light, -0.04),
    val displayS: TextStyle = style(48, FontWeight.Light, -0.03).copy(lineHeight = 1.0.em),
    val titleL: TextStyle = style(28, FontWeight.SemiBold, -0.02),
    val titleM: TextStyle = style(18, FontWeight.SemiBold),
    val titleSheet: TextStyle = style(20, FontWeight.SemiBold),
    val titleS: TextStyle = style(17, FontWeight.SemiBold),
    val timeRow: TextStyle = style(20),
    val timeRule: TextStyle = style(22),
    val bodyL: TextStyle = style(15),
    val body: TextStyle = style(14),
    val caption: TextStyle = style(13),
    val small: TextStyle = style(12),
    val overline: TextStyle = style(12, FontWeight.SemiBold, 0.12),
    val mono: TextStyle = style(13, family = GeistMono),
)

val LocalReveilleType = staticCompositionLocalOf { ReveilleType() }

/** Material's roles in Geist, for the components the app does not restyle (dialogs, pickers, snackbars). */
internal fun materialTypography(): Typography {
    val base = Typography()
    fun TextStyle.geist() = copy(fontFamily = Geist, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = base.displayLarge.geist(),
        displayMedium = base.displayMedium.geist(),
        displaySmall = base.displaySmall.geist(),
        headlineLarge = base.headlineLarge.geist(),
        headlineMedium = base.headlineMedium.geist(),
        headlineSmall = base.headlineSmall.geist(),
        titleLarge = base.titleLarge.geist(),
        titleMedium = base.titleMedium.geist(),
        titleSmall = base.titleSmall.geist(),
        bodyLarge = base.bodyLarge.geist(),
        bodyMedium = base.bodyMedium.geist(),
        bodySmall = base.bodySmall.geist(),
        labelLarge = base.labelLarge.geist(),
        labelMedium = base.labelMedium.geist(),
        labelSmall = base.labelSmall.geist(),
    )
}
