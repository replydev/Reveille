package com.replydev.reveille.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.provider.Settings
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.ui.components.Pill
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.theme.lineBox
import kotlinx.coroutines.flow.catch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * Full-screen alarm UI. Normal mode mirrors [RingingService.ringing] (Snooze/Dismiss go to the service). Fallback mode
 * ([fallbackIntent]) is opened by the fallback alarm notification when the service could not ring: it shows the
 * alarm and a Dismiss that acknowledges the fallback. Both work before the first unlock (directBootAware).
 */
class RingingActivity : ComponentActivity() {
    private var fallback by mutableStateOf<Armed?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full bleed on the accent, with dark system-bar icons.
        enableEdgeToEdge(SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT), SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT))
        fallback = fallbackArmed(intent)
        // Device-protected DataStore, so it reads before the first unlock too. Only labels depend on it: without it the
        // buttons still work and simply read "Snooze".
        val ringSettings = container.settings.settings.catch { Log.w("Reveille", "Cannot read settings for the ringing screen", it) }
        setContent {
            ReveilleTheme {
                BackHandler { /* the alarm must be dismissed or snoozed explicitly */ }
                val settings by ringSettings.collectAsStateWithLifecycle(initialValue = null)
                val ringing by RingingService.ringing.collectAsStateWithLifecycle()
                val current = ringing
                val fallbackAlarm = fallback
                when {
                    // The service took over (e.g. the ring guard succeeded): show the real ringing screen.
                    current != null -> RingingScreen(
                        title = current.title,
                        snoozeMinutes = settings?.snoozeMinutes?.coerceAtLeast(1),
                        autoSnoozeMinutes = settings?.let { RingDecisions.ringMinutes(it.maxRingMinutes) },
                        onSnooze = { RingingService.send(this@RingingActivity, RingingService.ACTION_SNOOZE) },
                        onDismiss = { RingingService.send(this@RingingActivity, RingingService.ACTION_DISMISS) },
                    )
                    fallbackAlarm != null -> RingingScreen(
                        title = "${fallbackAlarm.label} · ${TimeText.time(fallbackAlarm.at.atZone(ZoneId.systemDefault()).toLocalTime())}",
                        snoozeMinutes = null,
                        autoSnoozeMinutes = null,
                        onSnooze = null,
                        onDismiss = { dismissFallback(fallbackAlarm) },
                    )
                    else -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fallback = fallbackArmed(intent)
    }

    private fun dismissFallback(armed: Armed) {
        sendBroadcast(
            Intent(this, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FALLBACK_DISMISS)
                .putExtra(AlarmScheduler.EXTRA_ARMED, armed.encode()),
        )
        finish()
    }

    companion object {
        private const val EXTRA_FALLBACK = "fallback_armed"

        private fun fallbackArmed(intent: Intent?): Armed? = intent?.getStringExtra(EXTRA_FALLBACK)?.let(Armed::decode)

        fun fallbackIntent(context: Context, armed: Armed): Intent =
            Intent(context, RingingActivity::class.java)
                .putExtra(EXTRA_FALLBACK, armed.encode())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private val ringingDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

@Composable
private fun RingingScreen(
    title: String,
    snoozeMinutes: Int?,
    autoSnoozeMinutes: Int?,
    onSnooze: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(colors.accent)) {
        RingCircles(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = maxOf(96.dp, insets.calculateTopPadding() + 8.dp),
                    bottom = maxOf(40.dp, insets.calculateBottomPadding() + 16.dp),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(now.format(ringingDate).uppercase(), style = type.ringOverline, color = colors.onAccent)
            Text(
                TimeText.time(now.toLocalTime()),
                Modifier.padding(top = 18.dp).lineBox(type.displayXl, 1f),
                style = type.displayXl,
                color = colors.onAccent,
                maxLines = 1,
            )
            Row(
                Modifier.padding(top = 18.dp).height(36.dp).background(colors.onAccent.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReveilleIcon(R.drawable.ic_bell, null, tint = colors.onAccent, size = 18.dp)
                Text(title, style = type.input.copy(fontWeight = FontWeight.Medium), color = colors.onAccent, maxLines = 2, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Pill(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), container = colors.onAccent, content = colors.accent, height = 76.dp) {
                    Text("Dismiss", style = type.ringButton)
                }
                if (onSnooze != null) {
                    Pill(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth(),
                        container = Color.Transparent,
                        content = colors.onAccent,
                        border = BorderStroke(2.dp, colors.onAccent),
                        height = 76.dp,
                    ) {
                        Text(snoozeMinutes?.let { "Snooze $it min" } ?: "Snooze", style = type.ringButton)
                    }
                    autoSnoozeMinutes?.let {
                        Text(
                            "Auto-snoozes after $it min of ringing",
                            Modifier.padding(top = 6.dp).alpha(0.8f),
                            style = type.caption,
                            color = colors.onAccent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The three concentric circles behind the lower half (520/360/200 dp, centred where the mockup puts them). They
 * breathe slowly unless the system turned animations off.
 */
@Composable
private fun RingCircles(modifier: Modifier = Modifier) {
    val onAccent = ReveilleTheme.colors.onAccent
    val resolver = LocalContext.current.contentResolver
    val animate = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "ring pulse")
        transition.animateFloat(1f, 1.06f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale").value
    } else {
        1f
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height * (560f / 844f))
        val stroke = Stroke(1.dp.toPx())
        drawCircle(onAccent.copy(alpha = 0.10f), radius = 260.dp.toPx() * pulse, center = center, style = stroke)
        drawCircle(onAccent.copy(alpha = 0.14f), radius = 180.dp.toPx() * (1f + (pulse - 1f) * 1.5f), center = center, style = stroke)
        drawCircle(onAccent.copy(alpha = 0.06f), radius = 100.dp.toPx() * (1f + (pulse - 1f) * 2f), center = center)
    }
}
