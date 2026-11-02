package com.replydev.reveille.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import java.time.LocalTime
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
        fallback = fallbackArmed(intent)
        setContent {
            ReveilleTheme {
                BackHandler { /* the alarm must be dismissed or snoozed explicitly */ }
                val ringing by RingingService.ringing.collectAsStateWithLifecycle()
                val current = ringing
                val fallbackAlarm = fallback
                when {
                    // The service took over (e.g. the ring guard succeeded): show the real ringing screen.
                    current != null -> RingingScreen(
                        title = current.title,
                        onSnooze = { RingingService.send(this@RingingActivity, RingingService.ACTION_SNOOZE) },
                        onDismiss = { RingingService.send(this@RingingActivity, RingingService.ACTION_DISMISS) },
                    )
                    fallbackAlarm != null -> RingingScreen(
                        title = "${fallbackAlarm.label} · ${TimeText.time(fallbackAlarm.at.atZone(ZoneId.systemDefault()).toLocalTime())}",
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

@Composable
private fun RingingScreen(title: String, onSnooze: (() -> Unit)?, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(TimeText.time(now), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text("Dismiss", style = MaterialTheme.typography.titleLarge)
                }
                if (onSnooze != null) {
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                        Text("Snooze", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
