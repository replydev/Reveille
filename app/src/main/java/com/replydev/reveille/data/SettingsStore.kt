package com.replydev.reveille.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class RingSettings(
    val ringtoneUri: String? = null,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 9,
    val maxRingMinutes: Int = 10,
    val volumeRamp: Boolean = true,
)

class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<RingSettings> = store.data.map(::read)

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
        return RingSettings(
            ringtoneUri = prefs[RINGTONE],
            vibrate = prefs[VIBRATE] ?: defaults.vibrate,
            snoozeMinutes = prefs[SNOOZE_MINUTES] ?: defaults.snoozeMinutes,
            maxRingMinutes = prefs[MAX_RING_MINUTES] ?: defaults.maxRingMinutes,
            volumeRamp = prefs[VOLUME_RAMP] ?: defaults.volumeRamp,
        )
    }

    private companion object {
        val RINGTONE = stringPreferencesKey("ringtone_uri")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val MAX_RING_MINUTES = intPreferencesKey("max_ring_minutes")
        val VOLUME_RAMP = booleanPreferencesKey("volume_ramp")
        val CONFIRMED = stringSetPreferencesKey("confirmed_checks")
    }
}
