package com.replydev.reveille.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.replydev.reveille.R
import com.replydev.reveille.ui.theme.ReveilleTheme

/** A flat card: 1 dp outline, no elevation. Clickable when [onClick] is set. */
@Composable
fun ReveilleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    radius: Dp = 20.dp,
    color: Color = ReveilleTheme.colors.surface,
    border: Color? = ReveilleTheme.colors.outline,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val stroke = border?.let { BorderStroke(1.dp, it) }
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, contentColor = ReveilleTheme.colors.onBg, border = stroke, content = body)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, contentColor = ReveilleTheme.colors.onBg, border = stroke, content = body)
    }
}

/** Section label: 12 sp, semibold, +0.12 em, upper case. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = ReveilleTheme.colors.muted) {
    Text(text.uppercase(), modifier = modifier, style = ReveilleTheme.type.overline, color = color)
}

/** The small rounded square that marks a policy's (or a one-off's) colour. */
@Composable
fun PolicyDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, radius: Dp = 2.dp) {
    Box(modifier.size(size).background(color, RoundedCornerShape(radius)))
}

/** A round status dot, e.g. "Armed with system" or the Health badge. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, RoundedCornerShape(50)))
}

@Composable
fun ReveilleIcon(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = ReveilleTheme.colors.onBg, size: Dp = 20.dp) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

/** The danger pill: "1 issue", "Check failed". Tapping it opens Health. */
@Composable
fun StatusPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(50),
        color = colors.dangerContainer,
        contentColor = colors.dangerText,
        border = BorderStroke(1.dp, colors.dangerLine),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReveilleIcon(R.drawable.ic_warning, null, tint = colors.dangerText, size = 16.dp)
            Text(text, style = ReveilleTheme.type.caption.copy(fontWeight = FontWeight.Medium))
        }
    }
}

/** A short chip: 28 dp, radius 8, [ReveilleColors.surfaceHigh]. */
@Composable
fun Chip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.height(28.dp).background(ReveilleTheme.colors.surfaceHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun ChipText(text: String, color: Color = ReveilleTheme.colors.onBg, style: TextStyle = ReveilleTheme.type.caption) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A 1 dp dashed outline with rounded corners ([radius] null = fully rounded). */
fun Modifier.dashedBorder(color: Color, radius: Dp? = null): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val r = radius?.toPx() ?: (size.height / 2)
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}

/** A 1 dp line along the top edge (timeline days, sticky bars). */
fun Modifier.topDivider(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}
