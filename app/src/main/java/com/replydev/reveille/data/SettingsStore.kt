package com.replydev.reveille.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.replydev.reveille.alarm.RingTheme
import com.replydev.reveille.alarm.SoundRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class RingSettings(
    /** The default alarm sound. Replaces the old `ringtone_uri` setting, which is read as a [SoundRef.SystemTone]. */
    val sound: SoundRef = SoundRef.SystemDefault,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 9,
    val maxRingMinutes: Int = 10,
    val volumeRamp: Boolean = true,
    /** The ringing screen's look. Cosmetic: nothing on the alarm path depends on it. */
    val ringTheme: RingTheme = RingTheme.DAWN,
    /** policyId → theme override; no entry means "Default". May hold deleted policies until the next override write. */
    val policyRingThemes: Map<Long, RingTheme> = emptyMap(),
    /** policyId → sound override; no entry means "Default". */
    val policySounds: Map<Long, SoundRef> = emptyMap(),
    /** oneOffId → sound override; no entry means "Default". */
    val oneOffSounds: Map<Long, SoundRef> = emptyMap(),
    /** Files in device-protected `sounds/`, sorted by name. */
    val importedSounds: List<ImportedSound> = emptyList(),
) {
    /** How many choices (default, policy and one-off overrides) use imported sound [id]. */
    fun usesOf(id: String): Int {
        val ref = SoundRef.Imported(id)
        return (if (sound == ref) 1 else 0) + policySounds.values.count { it == ref } + oneOffSounds.values.count { it == ref }
    }
}

/** An imported sound: its file is `sounds/<id>`; [name] is what the user sees (the file name without extension). */
data class ImportedSound(val id: String, val name: String)

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
            prefs.remove(LEGACY_RINGTONE)
            prefs[SOUND] = next.sound.encode()
            prefs[POLICY_SOUNDS] = next.policySounds.map { (id, ref) -> "$id=${ref.encode()}" }.toSet()
            prefs[ONE_OFF_SOUNDS] = next.oneOffSounds.map { (id, ref) -> "$id=${ref.encode()}" }.toSet()
            prefs[IMPORTED_SOUNDS] = next.importedSounds.map { "${it.id}=${it.name}" }.toSet()
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

    /** Sets (or with null clears) [policyId]'s sound, dropping overrides of policies not in [livePolicyIds]. */
    suspend fun setPolicySound(policyId: Long, ref: SoundRef?, livePolicyIds: Set<Long>) {
        update { current ->
            val next = if (ref == null) current.policySounds - policyId else current.policySounds + (policyId to ref)
            current.copy(policySounds = next.filterKeys { it in livePolicyIds })
        }
    }

    /** Sets (or with null clears) [oneOffId]'s sound, dropping overrides of one-offs not in [liveOneOffIds]. */
    suspend fun setOneOffSound(oneOffId: Long, ref: SoundRef?, liveOneOffIds: Set<Long>) {
        update { current ->
            val next = if (ref == null) current.oneOffSounds - oneOffId else current.oneOffSounds + (oneOffId to ref)
            current.copy(oneOffSounds = next.filterKeys { it in liveOneOffIds })
        }
    }

    suspend fun addImportedSound(sound: ImportedSound) {
        update { it.copy(importedSounds = (it.importedSounds.filter { s -> s.id != sound.id } + sound).sortedBy { s -> s.name.lowercase() }) }
    }

    /** Removes [id] and, in the same write, points every choice that used it back to the system default. */
    suspend fun removeImportedSound(id: String) {
        val gone = SoundRef.Imported(id)
        fun SoundRef.reset() = if (this == gone) SoundRef.SystemDefault else this
        update { current ->
            current.copy(
                sound = current.sound.reset(),
                policySounds = current.policySounds.mapValues { it.value.reset() },
                oneOffSounds = current.oneOffSounds.mapValues { it.value.reset() },
                importedSounds = current.importedSounds.filter { it.id != id },
            )
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
            sound = prefs[SOUND]?.let(SoundRef::decode) ?: prefs[LEGACY_RINGTONE]?.let(SoundRef::SystemTone) ?: SoundRef.SystemDefault,
            vibrate = prefs[VIBRATE] ?: defaults.vibrate,
            snoozeMinutes = prefs[SNOOZE_MINUTES] ?: defaults.snoozeMinutes,
            maxRingMinutes = prefs[MAX_RING_MINUTES] ?: defaults.maxRingMinutes,
            volumeRamp = prefs[VOLUME_RAMP] ?: defaults.volumeRamp,
            ringTheme = RingTheme.fromName(prefs[RING_THEME]),
            policyRingThemes = parseOverrides(prefs[POLICY_RING_THEMES].orEmpty()),
            policySounds = parseSoundOverrides(prefs[POLICY_SOUNDS].orEmpty()),
            oneOffSounds = parseSoundOverrides(prefs[ONE_OFF_SOUNDS].orEmpty()),
            importedSounds = prefs[IMPORTED_SOUNDS].orEmpty().mapNotNull { entry ->
                val id = entry.substringBefore('=', "").ifEmpty { return@mapNotNull null }
                ImportedSound(id, entry.substringAfter('='))
            }.sortedBy { it.name.lowercase() },
        )
        lastRead = settings
        return settings
    }

    /** "id=NAME" entries; a malformed entry is skipped, an unknown name reads as Dawn. */
    private fun parseOverrides(entries: Set<String>): Map<Long, RingTheme> = entries.mapNotNull { entry ->
        val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
        id to RingTheme.fromName(entry.substringAfter('='))
    }.toMap()

    /** "id=<encoded ref>" entries, split on the first '='; a malformed entry is skipped. */
    private fun parseSoundOverrides(entries: Set<String>): Map<Long, SoundRef> = entries.mapNotNull { entry ->
        val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
        id to SoundRef.decode(entry.substringAfter('='))
    }.toMap()

    private companion object {
        val LEGACY_RINGTONE = stringPreferencesKey("ringtone_uri")
        val SOUND = stringPreferencesKey("sound")
        val POLICY_SOUNDS = stringSetPreferencesKey("policy_sounds")
        val ONE_OFF_SOUNDS = stringSetPreferencesKey("one_off_sounds")
        val IMPORTED_SOUNDS = stringSetPreferencesKey("imported_sounds")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val MAX_RING_MINUTES = intPreferencesKey("max_ring_minutes")
        val VOLUME_RAMP = booleanPreferencesKey("volume_ramp")
        val CONFIRMED = stringSetPreferencesKey("confirmed_checks")
        val RING_THEME = stringPreferencesKey("ring_theme")
        val POLICY_RING_THEMES = stringSetPreferencesKey("policy_ring_themes")
    }
}
