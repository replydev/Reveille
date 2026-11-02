package com.replydev.reveille.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.replydev.reveille.engine.Source

/** The fixed dark palette from docs/design/README.md. Screens read it through [ReveilleTheme.colors], not directly. */
object Palette {
    val Bg = Color(0xFF0B0D11)
    val NavBg = Color(0xFF101318)
    val SheetBg = Color(0xFF12151A)
    val Surface = Color(0xFF15181E)
    val SurfaceHigh = Color(0xFF1D2129)
    val Outline = Color(0xFF22262E)
    val OutlineStrong = Color(0xFF2A2F38)
    val OutlineDashed = Color(0xFF3A404A)
    val OnBg = Color(0xFFF3F4F6)
    val Muted = Color(0xFFA1A9B5)
    val Faint = Color(0xFF7D8693)
    val Accent = Color(0xFFFFB547)
    val OnAccent = Color(0xFF1A1205)
    val AccentContainer = Color(0xFF2A2418)
    val AccentLine = Color(0xFF3A3020)
    val Danger = Color(0xFFFF7A66)
    val DangerText = Color(0xFFFF8A75)
    val DangerContainer = Color(0xFF1E1513)
    val DangerLine = Color(0xFF3A2A25)
    val DangerTile = Color(0xFF3A221D)
    val DangerOnContainerMuted = Color(0xFFD8C2BC)
    val OnDanger = Color(0xFF1E0D09)
    val Success = Color(0xFF7EE0C3)
    val WidgetBg = Color(0xFF0F1216)

    /** Policy colours and their icon-tile tints, indexed by [policyColor]'s slot. */
    val PolicyColors = listOf(Color(0xFF8AB4FF), Color(0xFF7EE0C3), Color(0xFFF2A0C8), Color(0xFFC9B6FF))
    val PolicyTints = listOf(Color(0xFF1A2233), Color(0xFF15282A), Color(0xFF2A1823), Color(0xFF211C33))
}

@Immutable
class ReveilleColors(
    val bg: Color = Palette.Bg,
    val navBg: Color = Palette.NavBg,
    val sheetBg: Color = Palette.SheetBg,
    val surface: Color = Palette.Surface,
    val surfaceHigh: Color = Palette.SurfaceHigh,
    val outline: Color = Palette.Outline,
    val outlineStrong: Color = Palette.OutlineStrong,
    val outlineDashed: Color = Palette.OutlineDashed,
    val onBg: Color = Palette.OnBg,
    val muted: Color = Palette.Muted,
    /** Decorative only (switch knob, ":" separator, inactive day letters): never body text. */
    val faint: Color = Palette.Faint,
    val accent: Color = Palette.Accent,
    val onAccent: Color = Palette.OnAccent,
    val accentContainer: Color = Palette.AccentContainer,
    val accentLine: Color = Palette.AccentLine,
    val danger: Color = Palette.Danger,
    val dangerText: Color = Palette.DangerText,
    val dangerContainer: Color = Palette.DangerContainer,
    val dangerLine: Color = Palette.DangerLine,
    val dangerTile: Color = Palette.DangerTile,
    val dangerOnContainerMuted: Color = Palette.DangerOnContainerMuted,
    val onDanger: Color = Palette.OnDanger,
    val success: Color = Palette.Success,
)

val LocalReveilleColors = staticCompositionLocalOf { ReveilleColors() }

private fun slot(id: Long): Int = Math.floorMod(id, Palette.PolicyColors.size.toLong()).toInt()

/** A policy's colour, derived from its id: the database has no colour field. */
fun policyColor(id: Long): Color = Palette.PolicyColors[slot(id)]

/** The icon-tile background that goes with [policyColor]. */
fun policyTint(id: Long): Color = Palette.PolicyTints[slot(id)]

/** The colour of an occurrence: its first policy's, or the accent for one-off alarms. */
fun sourceColor(sources: List<Source>): Color =
    sources.filterIsInstance<Source.PolicyRule>().firstOrNull()?.let { policyColor(it.policyId) } ?: Palette.Accent

/** [sourceColor] for an armed alarm's source keys; a snooze has none and gets the accent. */
fun sourceKeyColor(keys: List<String>): Color = sourceColor(keys.mapNotNull(Source::parse))
