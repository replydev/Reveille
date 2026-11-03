package com.replydev.reveille.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.replydev.reveille.R
import com.replydev.reveille.ui.theme.ReveilleTheme

enum class PillStyle { Primary, Secondary, Outlined, Danger, Dashed }

/**
 * A fully rounded button. Disabled pills keep their shape and fade. A label too long for one line (German, French on a
 * narrow phone) wraps and the pill grows taller rather than cutting it off.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    textStyle: TextStyle = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
) {
    val colors = ReveilleTheme.colors
    val (container, content) = when (style) {
        PillStyle.Primary -> colors.accent to colors.onAccent
        PillStyle.Secondary -> colors.surfaceHigh to colors.onBg
        PillStyle.Outlined, PillStyle.Dashed -> Color.Transparent to colors.onBg
        PillStyle.Danger -> colors.dangerText to colors.onDanger
    }
    Pill(
        onClick = onClick,
        modifier = if (style == PillStyle.Dashed) modifier.dashedBorder(colors.outlineDashed) else modifier,
        container = container,
        content = content,
        border = if (style == PillStyle.Outlined) BorderStroke(1.dp, colors.outlineStrong) else null,
        height = height,
        enabled = enabled,
        contentPadding = contentPadding,
    ) { Text(text, Modifier.padding(vertical = 6.dp), style = textStyle, textAlign = TextAlign.Center) }
}

/** The base of every pill: fully rounded, centred content, at least [height] tall. */
@Composable
fun Pill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color,
    content: Color,
    border: BorderStroke? = null,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    body: @Composable () -> Unit,
) {
    // [height] is the size; without this, Material's 48 dp minimum would make every shorter pill 48 dp tall now
    // that the height is a minimum.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = height).alpha(if (enabled) 1f else 0.4f),
            shape = RoundedCornerShape(50),
            color = container,
            contentColor = content,
            border = border,
        ) {
            Box(Modifier.padding(contentPadding), contentAlignment = Alignment.Center) { body() }
        }
    }
}

/** A round icon-only button ([contentDescription] is required: it is also what Maestro selects on). */
@Composable
fun IconCircleButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    container: Color = ReveilleTheme.colors.surfaceHigh,
    tint: Color = ReveilleTheme.colors.muted,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size).alpha(if (enabled) 1f else 0.4f),
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) { ReveilleIcon(icon, contentDescription, tint = tint, size = iconSize) }
    }
}

/** A quiet text button (timeline "Skip", "Remove"): no fill, muted label, at least 36 dp tall. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = ReveilleTheme.colors.muted) {
    Pill(
        onClick = onClick,
        modifier = modifier,
        container = Color.Transparent,
        content = color,
        height = 36.dp,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, style = ReveilleTheme.type.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1) }
}

/** The extended FAB: 56 dp, radius 18, accent fill, the only element with a shadow. */
@Composable
fun ReveilleFab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp).shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
        shape = shape,
        color = colors.accent,
        contentColor = colors.onAccent,
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReveilleIcon(R.drawable.ic_add, null, tint = colors.onAccent)
            Text(text, style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
