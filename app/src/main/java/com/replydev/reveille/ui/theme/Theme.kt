package com.replydev.reveille.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Dark-only with a fixed palette, in both system modes: no dynamic colour. */
private val ColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentContainer,
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Accent,
    onSecondary = Palette.OnAccent,
    secondaryContainer = Palette.SurfaceHigh,
    onSecondaryContainer = Palette.OnBg,
    tertiary = Palette.Accent,
    onTertiary = Palette.OnAccent,
    background = Palette.Bg,
    onBackground = Palette.OnBg,
    surface = Palette.Surface,
    onSurface = Palette.OnBg,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    inverseSurface = Palette.OnBg,
    inverseOnSurface = Palette.Bg,
    inversePrimary = Palette.OnAccent,
    outline = Palette.OutlineStrong,
    outlineVariant = Palette.Outline,
    error = Palette.DangerText,
    onError = Palette.OnDanger,
    errorContainer = Palette.DangerContainer,
    onErrorContainer = Palette.DangerText,
    scrim = Palette.Bg,
)

private val Colors = ReveilleColors()
private val Type = ReveilleType()
private val MaterialType = materialTypography()

@Composable
fun ReveilleTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReveilleColors provides Colors, LocalReveilleType provides Type) {
        MaterialTheme(colorScheme = ColorScheme, typography = MaterialType, content = content)
    }
}

object ReveilleTheme {
    val colors: ReveilleColors
        @Composable @ReadOnlyComposable get() = LocalReveilleColors.current

    val type: ReveilleType
        @Composable @ReadOnlyComposable get() = LocalReveilleType.current
}
