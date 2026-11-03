package com.replydev.reveille.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.replydev.reveille.alarm.RingTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class RingSettings(
    val ringtoneUri: String? = null,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 9,
    val maxRingMinutes: Int = 10,
    val volumeRamp: Boolean = true,
    /** The ringing screen's look. Cosmetic: nothing on the alarm path depends on it. */
    val ringTheme: RingTheme = RingTheme.DAWN,
    /** policyId → theme override; no entry means "Default". May hold deleted policies until the next override write. */
    val policyRingThemes: Map<Long, RingTheme> = emptyMap(),
)

class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<RingSettings> = store.data.map(::read)

    /**
     * The settings this process read most recently, or null. The ringing screen starts from it so it can show the
     * right theme on its first frame without waiting for a read (the service has usually just read the settings).
     */
    @Volatile
    var lastRead: RingSettings? = null
        private set

    /** Ids of manual reliability checks the user has confirmed. */
    val confirmedChecks: Flow<Set<String>> = store.data.map { it[CONFIRMED] ?: emptySet() }

    suspend fun current(): RingSettings = settings.first()

    suspend fun update(transform: (RingSettings) -> RingSettings) {
        store.edit { prefs ->
            val next = transform(read(prefs))
            if (next.ringtoneUri == null) prefs.remove(RINGTONE) else prefs[RINGTONE] = next.ringtoneUri
            prefs[VIBRATE] = next.vibrate
            prefs[SNOOZE_MINUTES] = next.snoozeMinutes
            prefs[MAX_RING_MINUTES] = next.maxRingMinutes
            prefs[VOLUME_RAMP] = next.volumeRamp
            prefs[RING_THEME] = next.ringTheme.name
            prefs[POLICY_RING_THEMES] = next.policyRingThemes.map { (id, theme) -> "$id=${theme.name}" }.toSet()
        }
    }

    /** Sets (or with null clears) [policyId]'s theme override, dropping overrides of policies not in [livePolicyIds]. */
    suspend fun setPolicyRingTheme(policyId: Long, theme: RingTheme?, livePolicyIds: Set<Long>) {
        update { current ->
            val next = if (theme == null) current.policyRingThemes - policyId else current.policyRingThemes + (policyId to theme)
            current.copy(policyRingThemes = next.filterKeys { it in livePolicyIds })
        }
    }

    suspend fun setConfirmed(id: String, confirmed: Boolean) {
        store.edit { prefs ->
            val current = prefs[CONFIRMED] ?: emptySet()
            prefs[CONFIRMED] = if (confirmed) current + id else current - id
        }
    }

    private fun read(prefs: Preferences): RingSettings {
        val defaults = RingSettings()
        val settings = RingSettings(
            ringtoneUri = prefs[RINGTONE],
            vibrate = prefs[VIBRATE] ?: defaults.vibrate,
            snoozeMinutes = prefs[SNOOZE_MINUTES] ?: defaults.snoozeMinutes,
            maxRingMinutes = prefs[MAX_RING_MINUTES] ?: defaults.maxRingMinutes,
            volumeRamp = prefs[VOLUME_RAMP] ?: defaults.volumeRamp,
            ringTheme = RingTheme.fromName(prefs[RING_THEME]),
            policyRingThemes = parseOverrides(prefs[POLICY_RING_THEMES].orEmpty()),
        )
        lastRead = settings
        return settings
    }

    /** "id=NAME" entries; a malformed entry is skipped, an unknown name reads as Dawn. */
    private fun parseOverrides(entries: Set<String>): Map<Long, RingTheme> = entries.mapNotNull { entry ->
        val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
        id to RingTheme.fromName(entry.substringAfter('='))
    }.toMap()

    private companion object {
        val RINGTONE = stringPreferencesKey("ringtone_uri")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val MAX_RING_MINUTES = intPreferencesKey("max_ring_minutes")
        val VOLUME_RAMP = booleanPreferencesKey("volume_ramp")
        val CONFIRMED = stringSetPreferencesKey("confirmed_checks")
        val RING_THEME = stringPreferencesKey("ring_theme")
        val POLICY_RING_THEMES = stringSetPreferencesKey("policy_ring_themes")
    }
}
