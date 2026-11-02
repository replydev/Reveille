package com.replydev.reveille.alarm

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
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.delay
import java.time.LocalTime

class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ReveilleTheme {
                BackHandler { /* the alarm must be dismissed or snoozed explicitly */ }
                val ringing by RingingService.ringing.collectAsStateWithLifecycle()
                val current = ringing
                if (current == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    RingingScreen(
                        ringing = current,
                        onSnooze = { RingingService.send(this@RingingActivity, RingingService.ACTION_SNOOZE) },
                        onDismiss = { RingingService.send(this@RingingActivity, RingingService.ACTION_DISMISS) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RingingScreen(ringing: Ringing, onSnooze: () -> Unit, onDismiss: () -> Unit) {
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
            Text(ringing.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text("Dismiss", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text("Snooze", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
