package com.replydev.reveille.ui.settings

import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.alarm.RingDecisions
import com.replydev.reveille.container
import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SNOOZE_MINUTES = 30

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val update: ((RingSettings) -> RingSettings) -> Unit = { transform ->
        session.write("Could not save the setting.", guarded = false, action = { container.settings.update(transform) })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            update { it.copy(ringtoneUri = uri?.toString()) }
        }
    }

    TabScaffold(snackbarHostState = snackbar) { padding ->
        // Wait for the stored values: showing defaults first would let a quick tap overwrite them.
        val current = settings ?: return@TabScaffold
        // RingtoneManager reads the media database: off the main thread.
        val ringtoneName by produceState("…", current.ringtoneUri) {
            value = withContext(Dispatchers.IO) { ringtoneTitle(context, current.ringtoneUri) }
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TabHeader("Settings")
            Column(
                Modifier.fillMaxWidth().clickable {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.ringtoneUri?.toUri())
                    try {
                        picker.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        session.message(UiMessage("This phone has no sound picker."))
                    }
                },
            ) {
                Text("Alarm sound")
                Text(ringtoneName, style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow("Vibrate", current.vibrate) { value -> update { it.copy(vibrate = value) } }
            SwitchRow("Gradually increase volume", current.volumeRamp) { value -> update { it.copy(volumeRamp = value) } }
            StepperRow(
                label = "Snooze length",
                value = "${current.snoozeMinutes} min",
                onMinus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes - 1).coerceAtLeast(1)) } },
                onPlus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes + 1).coerceAtMost(MAX_SNOOZE_MINUTES)) } },
            )
            StepperRow(
                label = "Auto-snooze after ringing for",
                value = "${current.maxRingMinutes} min",
                onMinus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes - 1).coerceAtLeast(1)) } },
                onPlus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes + 1).coerceAtMost(RingDecisions.MAX_RING_MINUTES)) } },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        Text(value)
        TextButton(onClick = onPlus) { Text("+") }
    }
}

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return "Default alarm sound"
    return runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull() ?: "Custom sound"
}
