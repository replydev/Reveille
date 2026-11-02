package com.replydev.reveille.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.LineHeightStyle
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

private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun TextStyle.lineHeight(em: Double) = copy(lineHeight = em.em, lineHeightStyle = CssLineHeight)

/**
 * Lays a single line of text out in a CSS line box [em] × font size tall: the difference to the font's own height is
 * split evenly above and below, as a browser does. Compose does not shrink a single line below its font metrics, so
 * the display times (line-height 0.9–1) need this to sit where the mockups put them.
 */
fun Modifier.lineBox(style: TextStyle, em: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val box = (style.fontSize.toPx() * em).roundToInt()
    val top = (placeable.height - box) / 2
    val baseline = placeable[FirstBaseline]
    layout(placeable.width, box, if (baseline == AlignmentLine.Unspecified) emptyMap() else mapOf(FirstBaseline to baseline - top, LastBaseline to baseline - top)) {
        placeable.place(0, -top)
    }
}

/** The type roles of docs/design/README.md §1. */
@Immutable
class ReveilleType(
    val displayXl: TextStyle = style(124, FontWeight.Light, -0.05),
    val displayL: TextStyle = style(84, FontWeight.Light, -0.04),
    val displayM: TextStyle = style(72, FontWeight.Light, -0.04),
    /** The ":" between the one-off hour and minute boxes. */
    val displayColon: TextStyle = style(56, FontWeight.Light),
    val displayS: TextStyle = style(48, FontWeight.Light, -0.03),
    /** "None scheduled" in the hero, where a time would be. */
    val headline: TextStyle = style(34, FontWeight.Light, -0.02),
    val titleL: TextStyle = style(28, FontWeight.SemiBold, -0.02),
    val titleM: TextStyle = style(18, FontWeight.SemiBold),
    val titleSheet: TextStyle = style(20, FontWeight.SemiBold),
    val titleS: TextStyle = style(17, FontWeight.SemiBold),
    /** Titles inside cards (failing checks). */
    val cardTitle: TextStyle = style(16, FontWeight.SemiBold),
    val timeRow: TextStyle = style(20),
    val timeRule: TextStyle = style(22),
    val timeSmall: TextStyle = style(18),
    /** Text typed into the one-off label field. */
    val input: TextStyle = style(16),
    val bodyL: TextStyle = style(15),
    /** Primary pill labels: Save, Set for …. */
    val button: TextStyle = style(16, FontWeight.SemiBold),
    val body: TextStyle = style(14),
    /** Multi-line explanations (Policies intro, Health details): CSS line-height 1.45. */
    val paragraph: TextStyle = style(14).lineHeight(1.45),
    val paragraphSmall: TextStyle = style(13).lineHeight(1.45),
    val caption: TextStyle = style(13),
    val small: TextStyle = style(12),
    val overline: TextStyle = style(12, FontWeight.SemiBold, 0.12),
    /** The weekday above a day number (timeline, date tiles). */
    val dayOverline: TextStyle = style(11, FontWeight.SemiBold, 0.08),
    val dayNumber: TextStyle = style(20, FontWeight.Medium),
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

/** [this] with CSS line-height [em]: for multi-line copy that the mockups space with `line-height`. */
fun TextStyle.withLineHeight(em: Double): TextStyle = lineHeight(em)
