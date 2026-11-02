package com.replydev.reveille.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.replydev.reveille.MainActivity
import com.replydev.reveille.container
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.TimeText
import java.time.Instant
import java.time.ZoneId

/**
 * Next-alarm widget. Renders only [com.replydev.reveille.data.SchedulerState.armedFlow], i.e. exactly what
 * AlarmManager will fire, including snoozes and catch-ups (a past catch-up reads "now" until it rings).
 */
class ReveilleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val initial = container.schedulerState.armed()
        val healthy = container.reliability.allGood()
        provideContent {
            val armed by container.schedulerState.armedFlow.collectAsState(initial)
            GlanceTheme {
                WidgetContent(armed, healthy, Instant.now())
            }
        }
    }
}

@Composable
private fun WidgetContent(armed: Armed?, healthy: Boolean, now: Instant) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = armed?.let { TimeText.dayAndTime(it.at, now, ZoneId.systemDefault()) } ?: "No alarms",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
            if (!healthy) {
                Text(text = "  ⚠", style = TextStyle(color = GlanceTheme.colors.error, fontSize = 18.sp))
            }
        }
        if (armed != null) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${armed.label} · ${TimeText.relative(armed.at, now)}",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Button(text = "Skip", onClick = actionRunCallback<SkipNextAction>())
            }
        }
    }
}
