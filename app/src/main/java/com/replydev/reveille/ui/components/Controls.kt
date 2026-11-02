package com.replydev.reveille.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replydev.reveille.ui.theme.ReveilleTheme
import java.time.DayOfWeek
import java.util.Locale
import java.time.format.TextStyle as DayNameStyle

/** M T W T F S S, Monday first like [DayOfWeek.entries]. */
fun dayLetter(day: DayOfWeek): String = day.getDisplayName(DayNameStyle.NARROW, Locale.ENGLISH)

/** Material's switch in the palette. The thumb keeps its 24 dp size when off, as in the mockups. */
@Composable
fun ReveilleSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    val colors = ReveilleTheme.colors
    // The small switch (44×26) sits in an 84 dp tile that is itself the touch target; it drops Material's 48 dp minimum.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (small) 0.dp else LocalMinimumInteractiveComponentSize.current) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = if (small) modifier.scale(0.85f) else modifier,
        enabled = enabled,
        thumbContent = {},
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.faint,
            uncheckedTrackColor = colors.outlineStrong,
            uncheckedBorderColor = colors.outlineStrong,
        ),
    )
    }
}

/** − [value] + in a [ReveilleColors.surfaceHigh] pill. */
@Composable
fun PillStepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusDescription: String,
    plusDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    valueWidth: Dp = 64.dp,
    valueStyle: TextStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.Medium),
) {
    Row(
        modifier.height(height).background(ReveilleTheme.colors.surfaceHigh, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", minusDescription, onMinus, height)
        Text(value, Modifier.widthIn(min = valueWidth), style = valueStyle, textAlign = TextAlign.Center, maxLines = 1)
        StepperButton("+", plusDescription, onPlus, height)
    }
}

@Composable
private fun StepperButton(symbol: String, description: String, onClick: () -> Unit, size: Dp) {
    Pill(
        onClick = onClick,
        modifier = Modifier.size(size).semantics { contentDescription = description },
        container = Color.Transparent,
        content = ReveilleTheme.colors.onBg,
        height = size,
        contentPadding = PaddingValues(0.dp),
    ) { Text(symbol, fontSize = 18.sp, fontFamily = ReveilleTheme.type.body.fontFamily) }
}

/** Seven equal day toggles: selected in the accent, the rest on [ReveilleColors.surfaceHigh]. */
@Composable
fun DayToggleRow(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) colors.accent else colors.surfaceHigh)
                    .toggleable(on, role = Role.Checkbox) { onChange(if (on) selected - day else selected + day) }
                    .semantics { contentDescription = day.getDisplayName(DayNameStyle.FULL, Locale.ENGLISH) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dayLetter(day),
                    style = ReveilleTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) colors.onAccent else colors.muted,
                )
            }
        }
    }
}

/** The read-only week strip of a policy card: 20×22 cells in the policy [color] when the rule rings that day. */
@Composable
fun MiniDayCells(days: Set<DayOfWeek>, color: Color, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in days
            Box(
                Modifier.size(width = 20.dp, height = 22.dp).background(if (on) color else colors.surfaceHigh, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dayLetter(day),
                    style = ReveilleTheme.type.small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) colors.bg else colors.faint,
                )
            }
        }
    }
}

/** Two or more equal segments; the selected one is [ReveilleColors.onBg] with [ReveilleColors.bg] text. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Row(
        modifier.fillMaxWidth().background(colors.surfaceHigh, RoundedCornerShape(16.dp)).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) colors.onBg else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = ReveilleTheme.type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) colors.bg else colors.muted,
                )
            }
        }
    }
}

/** A filled single-line input with a leading icon and a [placeholder] that doubles as its label. */
@Composable
fun IconField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    radius: Dp = 12.dp,
    textStyle: TextStyle = ReveilleTheme.type.body,
) {
    val colors = ReveilleTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(height),
        singleLine = true,
        textStyle = textStyle.copy(color = colors.onBg),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(height).background(colors.surfaceHigh, RoundedCornerShape(radius)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReveilleIcon(icon, null, tint = colors.muted, size = 16.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = colors.muted, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** A [ReveilleColors.surface] tile with a small label above an editable value (the policy name). */
@Composable
fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = ReveilleTheme.type.titleM.copy(color = colors.onBg),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        decorationBox = { inner ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .borderOutline(16.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = ReveilleTheme.type.small, color = colors.muted)
                inner()
            }
        },
    )
}

/** The standard 1 dp card outline as a modifier, for tiles that are not a [ReveilleCard]. */
@Composable
fun Modifier.borderOutline(radius: Dp, color: Color = ReveilleTheme.colors.outline): Modifier =
    border(1.dp, color, RoundedCornerShape(radius))
