package com.replydev.reveille.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.BuildConfig
import com.replydev.reveille.R
import com.replydev.reveille.alarm.RingDecisions
import com.replydev.reveille.container
import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PillStepper
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.ReveilleSwitch
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.update.AboutRow
import com.replydev.reveille.ui.update.UpdatesSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SNOOZE_MINUTES = 30

@Composable
fun SettingsScreen(onOpenUpdate: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val updatePrefs by container.updates.store.prefs.collectAsStateWithLifecycle(initialValue = null)
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val saveFailed = stringResource(R.string.settings_save_failed)
    val noSoundPicker = stringResource(R.string.settings_no_sound_picker)
    val update: ((RingSettings) -> RingSettings) -> Unit = { transform ->
        session.write(saveFailed, guarded = false, action = { container.settings.update(transform) })
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
            TabHeader(stringResource(R.string.settings_title), bottom = 18.dp)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Group(stringResource(R.string.settings_sound)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(onClickLabel = stringResource(R.string.settings_choose_sound)) {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.ringtoneUri?.toUri())
                                try {
                                    picker.launch(intent)
                                } catch (_: ActivityNotFoundException) {
                                    session.message(UiMessage(noSoundPicker))
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
                            Text(stringResource(R.string.settings_alarm_sound), style = type.bodyL)
                            Text(ringtoneName, style = type.caption, color = colors.muted)
                        }
                        ReveilleIcon(R.drawable.ic_chevron_right, null, tint = colors.muted, size = 18.dp)
                    }
                    SwitchRow(stringResource(R.string.settings_vibrate), stringResource(R.string.settings_vibrate_hint), current.vibrate) { value -> update { it.copy(vibrate = value) } }
                    SwitchRow(
                        stringResource(R.string.settings_volume_ramp),
                        stringResource(R.string.settings_volume_ramp_hint),
                        current.volumeRamp,
                    ) { value ->
                        update { it.copy(volumeRamp = value) }
                    }
                }
                Group(stringResource(R.string.settings_snooze)) {
                    StepperRow(
                        label = R.string.settings_snooze_length,
                        minusDescription = R.string.settings_snooze_length_decrease,
                        plusDescription = R.string.settings_snooze_length_increase,
                        value = pluralStringResource(R.plurals.settings_minutes, current.snoozeMinutes, current.snoozeMinutes),
                        onMinus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes - 1).coerceAtLeast(1)) } },
                        onPlus = { update { it.copy(snoozeMinutes = (it.snoozeMinutes + 1).coerceAtMost(MAX_SNOOZE_MINUTES)) } },
                        divider = false,
                    )
                    StepperRow(
                        label = R.string.settings_auto_snooze,
                        minusDescription = R.string.settings_auto_snooze_decrease,
                        plusDescription = R.string.settings_auto_snooze_increase,
                        value = pluralStringResource(R.plurals.settings_minutes, current.maxRingMinutes, current.maxRingMinutes),
                        onMinus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes - 1).coerceAtLeast(1)) } },
                        onPlus = { update { it.copy(maxRingMinutes = (it.maxRingMinutes + 1).coerceAtMost(RingDecisions.MAX_RING_MINUTES)) } },
                    )
                }
                updatePrefs?.let { prefs ->
                    UpdatesSection(prefs, onView = onOpenUpdate, onMessage = { session.message(UiMessage(it)) })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline(stringResource(R.string.settings_about), Modifier.padding(horizontal = 4.dp))
                    AboutRow(BuildConfig.VERSION_NAME)
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
private fun StepperRow(
    @StringRes label: Int,
    @StringRes minusDescription: Int,
    @StringRes plusDescription: Int,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    divider: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (divider) Modifier.topDivider(ReveilleTheme.colors.outline) else Modifier)
            .heightIn(min = 68.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = ReveilleTheme.type.bodyL)
        PillStepper(
            value = value,
            onMinus = onMinus,
            onPlus = onPlus,
            minusDescription = stringResource(minusDescription),
            plusDescription = stringResource(plusDescription),
            height = 44.dp,
            valueWidth = 58.dp,
            valueStyle = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium),
        )
    }
}

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return context.getString(R.string.settings_default_sound)
    return runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull()
        ?: context.getString(R.string.settings_custom_sound)
}
