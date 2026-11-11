package com.replydev.reveille.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.replydev.reveille.R
import com.replydev.reveille.alarm.SoundRef
import com.replydev.reveille.data.ImportedSound
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens the system tone picker; returns a launcher taking the currently chosen tone (or null). */
@Composable
fun rememberTonePicker(onPicked: (SoundRef.SystemTone) -> Unit, onUnavailable: () -> Unit): (String?) -> Unit {
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                ?.let { picked(SoundRef.SystemTone(it.toString())) }
        }
    }
    return { current ->
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.toUri())
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}

/** What the user sees for [ref]; null means "Default" (no override). Tone titles are read off the main thread. */
@Composable
fun rememberSoundName(ref: SoundRef?, imported: List<ImportedSound>): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.sound_follow_default)
    val systemDefault = stringResource(R.string.sound_system_default)
    return when (ref) {
        null -> fallback
        SoundRef.SystemDefault -> systemDefault
        is SoundRef.Imported -> imported.firstOrNull { it.id == ref.id }?.name ?: systemDefault
        is SoundRef.SystemTone -> produceState("…", ref.uri) { value = withContext(Dispatchers.IO) { toneTitle(context, ref.uri) } }.value
    }
}

fun toneTitle(context: Context, uri: String): String =
    runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull()
        ?: context.getString(R.string.settings_custom_sound)

/** One preview at a time, at alarm volume; stops when the composable leaves. [playing] is the previewed id, or null. */
class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null)
        private set

    /** Plays imported sound [id] from [path]; returns false if it can't be played. */
    fun toggle(id: String, path: String): Boolean {
        val wasPlaying = playing == id
        stop()
        if (wasPlaying) return true
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing = id
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        playing = null
    }
}

@Composable
fun rememberSoundPreview(): SoundPreview {
    val context = LocalContext.current
    val preview = remember { SoundPreview(context.applicationContext) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    return preview
}

/**
 * The override choice for a policy or a one-off: Default (null), the system default, a system tone (opens the picker
 * through [onPickTone]) and each imported sound. Picking one calls [onPick] and the caller closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundChoiceSheet(
    title: String,
    current: SoundRef?,
    imported: List<ImportedSound>,
    onDismiss: () -> Unit,
    onPick: (SoundRef?) -> Unit,
    onPickTone: () -> Unit,
    leading: @Composable () -> Unit = {},
) {
    val colors = ReveilleTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = colors.sheetBg,
        contentColor = colors.onBg,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).selectableGroup()) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                Text(title, style = ReveilleTheme.type.titleSheet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChoiceRow(stringResource(R.string.sound_follow_default), current == null) { onPick(null) }
            ChoiceRow(stringResource(R.string.sound_system_default), current == SoundRef.SystemDefault) { onPick(SoundRef.SystemDefault) }
            ChoiceRow(
                if (current is SoundRef.SystemTone) rememberSoundName(current, imported) else stringResource(R.string.sound_system_tone_pick),
                current is SoundRef.SystemTone,
                onClick = onPickTone,
            )
            imported.forEach { sound ->
                ChoiceRow(sound.name, current == SoundRef.Imported(sound.id)) { onPick(SoundRef.Imported(sound.id)) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted))
        Text(text, style = ReveilleTheme.type.bodyL, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
