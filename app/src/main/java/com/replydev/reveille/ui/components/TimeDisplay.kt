package com.replydev.reveille.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.replydev.reveille.text.TimeParts
import com.replydev.reveille.ui.theme.lineBox

/**
 * A large time: the digits in [style] and, on a 12-hour phone, the period ("AM") after them in [periodStyle], 4 dp
 * apart and on the same baseline. The digits sit in a CSS line box [lineBox] × their font size tall (see [lineBox]).
 * The period is measured first, so a narrow screen clips the digits rather than dropping "AM" / "PM".
 */
@Composable
fun TimeDisplay(
    parts: TimeParts,
    style: TextStyle,
    periodStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    lineBox: Float = 1f,
) {
    Row(modifier) {
        Text(
            parts.digits,
            Modifier.weight(1f, fill = false).lineBox(style, lineBox).alignByBaseline(),
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        parts.period?.let {
            Spacer(Modifier.width(4.dp))
            Text(it, Modifier.alignByBaseline(), style = periodStyle, color = color, maxLines = 1, softWrap = false)
        }
    }
}
