package com.replydev.reveille.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.alarm.SoundRef
import com.replydev.reveille.container
import com.replydev.reveille.data.ImportResult
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.EditorTopBar
import com.replydev.reveille.ui.components.IconCircleButton
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.policyColor

/** Settings › Alarm sound (AlarmSound.dc.html). Every choice saves immediately. */
@Composable
fun AlarmSoundScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val policies by container.repository.policies.collectAsStateWithLifecycle(initialValue = null)
    val colors = ReveilleTheme.colors
    val preview = rememberSoundPreview()
    val saveFailed = stringResource(R.string.sound_save_failed)
    val noPicker = stringResource(R.string.settings_no_sound_picker)
    val previewFailed = stringResource(R.string.sound_preview_failed)
    val unreadable = stringResource(R.string.sound_import_unreadable)
    val tooLarge = stringResource(R.string.sound_import_too_large)
    val notAudio = stringResource(R.string.sound_import_not_audio)
    val resources = LocalResources.current
    var editingPolicy by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    // Which choice a picked system tone goes to: the default (null) or a policy.
    var toneTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    fun setDefault(ref: SoundRef) = session.write(saveFailed, guarded = false, action = { container.settings.update { it.copy(sound = ref) } })
    fun setPolicy(id: Long, ref: SoundRef?) {
        val live = policies.orEmpty().map { it.policy.id }.toSet()
        session.write(saveFailed, guarded = false, action = { container.settings.setPolicySound(id, ref, live) })
    }

    val pickTone = rememberTonePicker(
        onPicked = { tone -> toneTarget?.let { setPolicy(it, tone) } ?: setDefault(tone) },
        onUnavailable = { session.message(UiMessage(noPicker)) },
    )
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        session.write(
            unreadable,
            guarded = false,
            // importUri reports every failure as a result; the finally covers cancellation.
            action = { try { container.sounds.importUri(context.contentResolver, uri) } finally { importing = false } },
            onSuccess = { result ->
                val text = when (result) {
                    is ImportResult.Imported -> resources.getString(R.string.sound_imported, result.sound.name)
                    ImportResult.TooLarge -> tooLarge
                    ImportResult.NotAudio -> notAudio
                    ImportResult.Unreadable -> unreadable
                }
                session.message(UiMessage(text))
            },
        )
    }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { EditorTopBar(stringResource(R.string.settings_alarm_sound), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { padding ->
        // Wait for the stored values: a quick tap must not overwrite them with defaults.
        val current = settings ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline(stringResource(R.string.sound_default_group), Modifier.padding(horizontal = 4.dp))
                ReveilleCard(Modifier.fillMaxWidth().selectableGroup(), padding = PaddingValues(0.dp)) {
                    RadioRow(stringResource(R.string.sound_system_default), null, current.sound == SoundRef.SystemDefault, divider = false, onClick = {
                        setDefault(SoundRef.SystemDefault)
                    })
                    val tone = current.sound as? SoundRef.SystemTone
                    RadioRow(
                        stringResource(R.string.sound_system_tone),
                        tone?.let { rememberSoundName(it, current.importedSounds) } ?: stringResource(R.string.sound_system_tone_pick),
                        tone != null,
                        onClick = {
                            toneTarget = null
                            pickTone(tone?.uri)
                        },
                    )
                    current.importedSounds.forEach { sound ->
                        RadioRow(sound.name, null, current.sound == SoundRef.Imported(sound.id), onClick = { setDefault(SoundRef.Imported(sound.id)) }) {
                            val playing = preview.playing == sound.id
                            IconCircleButton(
                                if (playing) R.drawable.ic_stop else R.drawable.ic_play,
                                if (playing) stringResource(R.string.sound_stop) else stringResource(R.string.sound_play, sound.name),
                                {
                                    if (!preview.toggle(sound.id, container.sounds.file(sound.id).path)) session.message(UiMessage(previewFailed))
                                },
                                size = 44.dp, // touch targets are >= 44 dp
                                container = Color.Transparent,
                            )
                            IconCircleButton(R.drawable.ic_delete, stringResource(R.string.sound_delete, sound.name), { deleting = sound.id }, size = 44.dp, container = Color.Transparent)
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .topDivider(colors.outline)
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !importing) { pickFile.launch(arrayOf("audio/*")) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ReveilleIcon(R.drawable.ic_upload, null, tint = colors.accent, size = 20.dp)
                        Text(
                            stringResource(if (importing) R.string.sound_importing else R.string.sound_import),
                            style = ReveilleTheme.type.bodyL,
                            color = if (importing) colors.muted else colors.accent,
                        )
                    }
                }
            }
            val rows = policies.orEmpty()
            if (rows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline(stringResource(R.string.sound_per_policy), Modifier.padding(horizontal = 4.dp))
                    ReveilleCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                        rows.forEachIndexed { index, item ->
                            val id = item.policy.id
                            val ref = current.policySounds[id]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (index > 0) Modifier.topDivider(colors.outline) else Modifier)
                                    .heightIn(min = 56.dp)
                                    .clickable { editingPolicy = id }
                                    .padding(start = 16.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PolicyDot(policyColor(id), size = 10.dp, radius = 3.dp)
                                Text(item.policy.name, Modifier.weight(1f), style = ReveilleTheme.type.bodyL, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    rememberSoundName(ref, current.importedSounds),
                                    Modifier.weight(1f, fill = false),
                                    style = ReveilleTheme.type.body,
                                    color = if (ref == null) colors.muted else colors.onBg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ReveilleIcon(R.drawable.ic_chevron_right, null, tint = colors.muted, size = 16.dp)
                            }
                        }
                    }
                }
            }
        }

        policies.orEmpty().firstOrNull { it.policy.id == editingPolicy }?.let { item ->
            val id = item.policy.id
            SoundChoiceSheet(
                title = item.policy.name,
                current = current.policySounds[id],
                imported = current.importedSounds,
                onDismiss = { editingPolicy = null },
                onPick = { ref ->
                    editingPolicy = null
                    setPolicy(id, ref)
                },
                onPickTone = {
                    editingPolicy = null
                    toneTarget = id
                    pickTone((current.policySounds[id] as? SoundRef.SystemTone)?.uri)
                },
                leading = { PolicyDot(policyColor(id), size = 10.dp, radius = 3.dp) },
            )
        }

        current.importedSounds.firstOrNull { it.id == deleting }?.let { sound ->
            val uses = current.usesOf(sound.id)
            ConfirmDialog(
                title = stringResource(R.string.sound_delete_title),
                text = if (uses == 0) stringResource(R.string.sound_delete_unused, sound.name)
                else pluralStringResource(R.plurals.sound_delete_in_use, uses, sound.name, uses),
                confirmLabel = stringResource(R.string.common_delete),
                onDismiss = { deleting = null },
                onConfirm = {
                    deleting = null
                    if (preview.playing == sound.id) preview.stop()
                    session.write(saveFailed, guarded = false, action = { container.sounds.delete(sound.id) })
                },
            )
        }
    }
}

/** A radio row with an optional caption and trailing actions; the row (not the actions) selects. */
@Composable
private fun RadioRow(
    title: String,
    caption: String?,
    selected: Boolean,
    divider: Boolean = true,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val colors = ReveilleTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (divider) Modifier.topDivider(colors.outline) else Modifier).heightIn(min = 56.dp).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = ReveilleTheme.type.bodyL, maxLines = 1, overflow = TextOverflow.Ellipsis)
                caption?.let { Text(it, style = ReveilleTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        trailing()
    }
}
