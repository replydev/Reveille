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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.replydev.reveille.BuildConfig
import com.replydev.reveille.R
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PillStepper
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.ReveilleSwitch
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.ReveilleTheme
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
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
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
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            TabHeader("Settings", bottom = 18.dp)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Group("Sound") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(onClickLabel = "Choose the alarm sound") {
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
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(36.dp).background(colors.accentContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            ReveilleIcon(R.drawable.ic_music, null, tint = colors.accent, size = 18.dp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Alarm sound", style = type.bodyL)
                            Text(ringtoneName, style = type.caption, color = colors.muted)
                        }
                        ReveilleIcon(R.drawable.ic_chevron_right, null, tint = colors.muted, size = 18.dp)
                    }
                    SwitchRow("Vibrate", "Pulse while the alarm rings", current.vibrate) { value -> update { it.copy(vibrate = value) } }
                    SwitchRow("Gradually increase volume", "Starts quiet, reaches full volume", current.volumeRamp) { value ->
                        update { it.copy(volumeRamp = value) }
                    }
                }
                Group("Snooze") {
                    StepperRow(
                        label = "Snooze length",
                        value = "${current.snoozeMinutes} min",
                        onMinus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes - 1).coerceAtLeast(1)) } },
                        onPlus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes + 1).coerceAtMost(MAX_SNOOZE_MINUTES)) } },
                        divider = false,
                    )
                    StepperRow(
                        label = "Auto-snooze after ringing for",
                        value = "${current.maxRingMinutes} min",
                        onMinus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes - 1).coerceAtLeast(1)) } },
                        onPlus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes + 1).coerceAtMost(RingDecisions.MAX_RING_MINUTES)) } },
                    )
                }
                Group("About") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(40.dp).background(colors.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            ReveilleIcon(R.drawable.ic_mark, null, tint = colors.onAccent, size = 22.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Reveille", style = type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                            Text(BuildConfig.VERSION_NAME, style = type.mono, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

/** An overline and a card holding [content]'s rows. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(title, Modifier.padding(horizontal = 4.dp))
        ReveilleCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), content = content)
    }
}

/** The whole row toggles; the switch only shows the state. */
@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .topDivider(ReveilleTheme.colors.outline)
            .heightIn(min = 60.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = ReveilleTheme.type.bodyL)
            Text(hint, style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
        }
        ReveilleSwitch(checked, onCheckedChange = null)
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, divider: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (divider) Modifier.topDivider(ReveilleTheme.colors.outline) else Modifier)
            .heightIn(min = 68.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = ReveilleTheme.type.bodyL)
        PillStepper(
            value = value,
            onMinus = onMinus,
            onPlus = onPlus,
            minusDescription = "Decrease ${label.lowercase()}",
            plusDescription = "Increase ${label.lowercase()}",
            height = 44.dp,
            valueWidth = 58.dp,
            valueStyle = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium),
        )
    }
}

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return "Default alarm sound"
    return runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull() ?: "Custom sound"
}
