package com.replydev.reveille.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.replydev.reveille.MainActivity
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.text.Labels
import com.replydev.reveille.ui.components.CHECKS_FAILED
import com.replydev.reveille.ui.home.issuesLabel
import com.replydev.reveille.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Launcher sizes the widget lays out for: 2×2, 4×1 and 4×2 (and anything larger). */
private val Small = DpSize(110.dp, 110.dp)
private val Row4x1 = DpSize(250.dp, 56.dp)
private val Large = DpSize(250.dp, 110.dp)

/**
 * Next-alarm widget. Renders only [com.replydev.reveille.data.SchedulerState.armedFlow], i.e. exactly what
 * AlarmManager will fire, including snoozes and catch-ups (a past catch-up reads "now" until it rings).
 * Glance cannot load custom fonts, so it uses the system sans (light for the big time).
 */
class ReveilleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Row4x1, Large))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val initial = container.schedulerState.armed()
        val failing = try {
            container.reliability.checks().count { !it.ok }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Reveille", "Reliability check failed", e)
            CHECKS_FAILED
        }
        provideContent {
            val armed by container.schedulerState.armedFlow.collectAsState(initial)
            WidgetContent(context, armed, failing, Instant.now())
        }
    }
}

private fun color(value: Color) = ColorProvider(value)

private val LightSans = FontFamily("sans-serif-light")

/** Opens the app, optionally on the one-off sheet or on Health. An existing task is reused, not stacked. */
private fun open(context: Context, target: String? = null): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { if (target != null) putExtra(MainActivity.EXTRA_OPEN, target) },
)

@Composable
private fun WidgetContent(context: Context, armed: Armed?, failing: Int, now: Instant) {
    val size = LocalSize.current
    val unhealthy = failing != 0
    when {
        armed == null -> Empty(context, row = size.width >= Large.width || size.height < Small.height)
        size.height < Small.height -> Compact(context, armed, failing, now)
        size.width >= Large.width -> Wide(context, armed, failing, now)
        unhealthy -> SmallUnhealthy(context, armed, failing)
        else -> SmallArmed(context, armed, now)
    }
}

/** The dark card; with [border] it is drawn 1 dp inside a [Palette.DangerLine] frame (Glance has no borders). */
@Composable
private fun Card(onClick: Action, radius: Dp = 28.dp, border: Boolean = false, content: @Composable () -> Unit) {
    if (!border) {
        Box(GlanceModifier.fillMaxSize().cornerRadius(radius).background(color(Palette.WidgetBg)).clickable(onClick)) { content() }
        return
    }
    Box(GlanceModifier.fillMaxSize().cornerRadius(radius).background(color(Palette.DangerLine)).padding(1.dp).clickable(onClick)) {
        Box(GlanceModifier.fillMaxSize().cornerRadius(radius - 1.dp).background(color(Palette.WidgetBg))) { content() }
    }
}

@Composable
private fun Time(armed: Armed, size: TextUnit, tint: Color) {
    Text(
        Labels.of(LocalContext.current).formats.time(armed.at.atZone(ZoneId.systemDefault()).toLocalTime()),
        style = TextStyle(color = color(tint), fontSize = size, fontFamily = LightSans),
        maxLines = 1,
    )
}

private fun subtitle(context: Context, armed: Armed, now: Instant): String {
    val labels = Labels.of(context)
    val label = if (armed.isSnooze) context.getString(R.string.today_snoozed, armed.label) else armed.label
    return context.getString(R.string.widget_subtitle, labels.dayPart(armed.at, now, ZoneId.systemDefault()), label)
}

@Composable
private fun Issues(failing: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.ic_warning), contentDescription = null, modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(color(Palette.DangerText)))
        Spacer(GlanceModifier.width(6.dp))
        Text(issuesLabel(LocalContext.current.resources, failing), style = TextStyle(color = color(Palette.DangerText), fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun SkipButton(armed: Armed) {
    Box(
        GlanceModifier
            .height(44.dp)
            .cornerRadius(22.dp)
            .background(color(Palette.SurfaceHigh))
            .padding(horizontal = 18.dp)
            // The displayed occurrence: a tap on a stale widget must not skip whatever is armed by then.
            .clickable(actionRunCallback<SkipNextAction>(actionParametersOf(SkipNextAction.KEY to armed.key, SkipNextAction.AT to armed.at.toEpochMilli()))),
        contentAlignment = Alignment.Center,
    ) {
        Text(LocalContext.current.getString(R.string.today_skip), style = TextStyle(color = color(Palette.OnBg), fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

/** 4×2: "Next wake-up" and the relative time, the big time with day and label, and Skip. */
@Composable
private fun Wide(context: Context, armed: Armed, failing: Int, now: Instant) {
    val unhealthy = failing != 0
    Card(if (unhealthy) open(context, MainActivity.OPEN_HEALTH) else open(context), border = unhealthy) {
        Column(GlanceModifier.fillMaxSize().padding(20.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (unhealthy) {
                    Issues(failing)
                } else {
                    Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(color(Palette.Accent))) {}
                    Spacer(GlanceModifier.width(8.dp))
                    Text(context.getString(R.string.today_next_wake_up), style = TextStyle(color = color(Palette.Muted), fontSize = 13.sp))
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(Labels.of(context).relative(armed.at, now), style = TextStyle(color = color(Palette.Muted), fontSize = 13.sp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(GlanceModifier.defaultWeight()) {
                    Time(armed, 56.sp, Palette.OnBg)
                    Text(subtitle(context, armed, now), style = TextStyle(color = color(Palette.OnBg), fontSize = 15.sp), maxLines = 1)
                }
                SkipButton(armed)
            }
        }
    }
}

/** 2×2 on the accent: the app mark, the time, day and label. */
@Composable
private fun SmallArmed(context: Context, armed: Armed, now: Instant) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(color(Palette.Accent)).clickable(open(context))) {
        Column(GlanceModifier.fillMaxSize().padding(18.dp)) {
            Image(ImageProvider(R.drawable.ic_mark), contentDescription = null, modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(color(Palette.OnAccent)))
            Spacer(GlanceModifier.defaultWeight())
            Time(armed, 44.sp, Palette.OnAccent)
            Text(subtitle(context, armed, now), style = TextStyle(color = color(Palette.OnAccent), fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

/** 2×2 when a reliability check fails: dark, danger frame, "N issues", the time and "Tap to fix". */
@Composable
private fun SmallUnhealthy(context: Context, armed: Armed, failing: Int) {
    Card(open(context, MainActivity.OPEN_HEALTH), border = true) {
        Column(GlanceModifier.fillMaxSize().padding(18.dp)) {
            Issues(failing)
            Spacer(GlanceModifier.defaultWeight())
            Time(armed, 44.sp, Palette.OnBg)
            Text(context.getString(R.string.widget_tap_to_fix), style = TextStyle(color = color(Palette.Muted), fontSize = 14.sp))
        }
    }
}

/** 4×1 with an alarm: the time, day and label in one row, and Skip. */
@Composable
private fun Compact(context: Context, armed: Armed, failing: Int, now: Instant) {
    val unhealthy = failing != 0
    Card(if (unhealthy) open(context, MainActivity.OPEN_HEALTH) else open(context), radius = 24.dp, border = unhealthy) {
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Time(armed, 32.sp, Palette.OnBg)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                if (unhealthy) Issues(failing)
                Text(subtitle(context, armed, now), style = TextStyle(color = color(Palette.Muted), fontSize = 14.sp), maxLines = 1)
            }
            SkipButton(armed)
        }
    }
}

/** Nothing armed: says so, with a round + that opens the one-off sheet. */
@Composable
private fun Empty(context: Context, row: Boolean) {
    val add = @Composable {
        Box(
            GlanceModifier.size(40.dp).cornerRadius(20.dp).background(color(Palette.SurfaceHigh)).clickable(open(context, MainActivity.OPEN_ONE_OFF)),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_add), contentDescription = context.getString(R.string.widget_new_one_off), modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(color(Palette.OnBg)))
        }
    }
    val text = @Composable { modifier: GlanceModifier ->
        Text(context.getString(R.string.widget_no_alarms), modifier = modifier, style = TextStyle(color = color(Palette.Muted), fontSize = 16.sp))
    }
    Card(open(context), radius = 24.dp) {
        if (row) {
            Row(GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                text(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(14.dp))
                add()
            }
        } else {
            Column(GlanceModifier.fillMaxSize().padding(18.dp)) {
                text(GlanceModifier)
                Spacer(GlanceModifier.defaultWeight())
                add()
            }
        }
    }
}
