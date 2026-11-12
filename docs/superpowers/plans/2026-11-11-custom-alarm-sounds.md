# Custom Alarm Sounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users import audio files (MP3 etc.) and use them, or system tones, as the global alarm sound and as per-policy and per-one-off overrides, without ever losing a wake-up.

**Architecture:** Imported files are copied into device-protected storage (`files/sounds/<uuid>`) by `data/SoundLibrary`. Choices are `SoundRef` values (`alarm/SoundRef.kt`) stored in the existing device-protected DataStore next to the theme overrides. On ringing, the pure `resolveSound` picks the one-off override, then the policy override, then the global choice. `soundCandidates` turns that into an ordered list (chosen → system default → bundled), and `RingingService` prepares the first entry that works.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, MediaPlayer, Robolectric/JUnit4, Maestro.

**Spec:** `docs/superpowers/specs/2026-11-11-custom-alarm-sounds-design.md`

**Before every commit:** `./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` must pass, and lint must report 0 errors. Use conventional commits, unsigned, with no AI attribution. Intermediate commits use `refactor`, `test` or `feat` as stated in each task.

---

## File map

| File | Responsibility |
|---|---|
| `app/src/main/java/com/replydev/reveille/alarm/SoundRef.kt` (new) | `SoundRef`, encode/decode, `resolveSound`, `SoundSource`, `soundCandidates`, `prepareFirst` |
| `app/src/main/java/com/replydev/reveille/data/SettingsStore.kt` | New `RingSettings` fields, legacy migration, override and library writes |
| `app/src/main/java/com/replydev/reveille/data/SoundLibrary.kt` (new) | Import, delete and orphan sweep of files in `sounds/` |
| `app/src/main/java/com/replydev/reveille/alarm/RingingService.kt` | `prepare` walks `soundCandidates` |
| `app/src/main/java/com/replydev/reveille/AppContainer.kt`, `ReveilleApp.kt` | Wire `SoundLibrary`; sweep orphans at start once the user is unlocked |
| `app/src/main/java/com/replydev/reveille/ui/settings/SoundUi.kt` (new) | Shared UI: `rememberTonePicker`, `rememberSoundName`, `SoundPreview`, `SoundChoiceSheet` |
| `app/src/main/java/com/replydev/reveille/ui/settings/AlarmSoundScreen.kt` (new) | Settings › Alarm sound screen |
| `app/src/main/java/com/replydev/reveille/ui/settings/SettingsScreen.kt` | The Alarm sound row pushes the new screen |
| `app/src/main/java/com/replydev/reveille/ui/ReveilleNavHost.kt` | `Routes.ALARM_SOUND` |
| `app/src/main/java/com/replydev/reveille/ui/oneoff/OneOffEditorScreen.kt` | Sound row and its save/delete |
| `app/src/main/res/values*/strings.xml` | New strings in 5 locales |
| `app/src/main/res/drawable/ic_play.xml`, `ic_stop.xml`, `ic_upload.xml` (new) | Icons |
| `.maestro/sounds/alarm_sound.yaml`, `.maestro/sounds/test_tone.mp3`, `scripts/maestro-sounds.sh` (new) | UI flow |
| Docs | Spec, design README, mockups, AGENTS.md, build report |

---

### Task 1: SoundRef and its encoding

**Files:**
- Create: `app/src/main/java/com/replydev/reveille/alarm/SoundRef.kt`
- Test: `app/src/test/java/com/replydev/reveille/alarm/SoundRefTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.replydev.reveille.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundRefTest {
    @Test
    fun roundTrips() {
        listOf(
            SoundRef.SystemDefault,
            SoundRef.SystemTone("content://media/internal/audio/media/12?title=a=b"),
            SoundRef.Imported("0b6f2c3e-8d1a-4a43-9c55-2f4d1d7e9a10"),
        ).forEach { assertEquals(it, SoundRef.decode(it.encode())) }
    }

    @Test
    fun encodings() {
        assertEquals("default", SoundRef.SystemDefault.encode())
        assertEquals("tone:content://x", SoundRef.SystemTone("content://x").encode())
        assertEquals("file:abc", SoundRef.Imported("abc").encode())
    }

    @Test
    fun anythingUnreadableIsTheSystemDefault() {
        listOf(null, "", "garbage", "tone:", "file:", "file:../escape", "file:a/b").forEach {
            assertEquals(it, SoundRef.SystemDefault, SoundRef.decode(it))
        }
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundRefTest'`
Expected: compilation FAILS ("Unresolved reference: SoundRef").

- [ ] **Step 3: Implement**

```kotlin
package com.replydev.reveille.alarm

/**
 * Which sound an alarm plays (docs/superpowers/specs/2026-11-11-custom-alarm-sounds-design.md). Stored with [encode];
 * [decode] never throws, and anything unreadable is [SystemDefault].
 */
sealed interface SoundRef {
    fun encode(): String

    /** The phone's default alarm sound (RingtoneManager.getDefaultUri(TYPE_ALARM)). */
    data object SystemDefault : SoundRef {
        override fun encode() = "default"
    }

    /** A tone from the system picker. Not readable before the first unlock; the fallback chain covers that. */
    data class SystemTone(val uri: String) : SoundRef {
        override fun encode() = "tone:$uri"
    }

    /** A file the user imported into device-protected storage (data/SoundLibrary). */
    data class Imported(val id: String) : SoundRef {
        override fun encode() = "file:$id"
    }

    companion object {
        fun decode(value: String?): SoundRef = when {
            value == null -> SystemDefault
            value.startsWith("tone:") && value.length > 5 -> SystemTone(value.substring(5))
            // An id is a file name in sounds/: never a path.
            value.startsWith("file:") && value.length > 5 && value.substring(5).let { '/' !in it && it != "." && it != ".." && !it.startsWith("..") } ->
                Imported(value.substring(5))
            else -> SystemDefault
        }
    }
}
```

- [ ] **Step 4: Run it and check that it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundRefTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/replydev/reveille/alarm/SoundRef.kt app/src/test/java/com/replydev/reveille/alarm/SoundRefTest.kt
git commit -m "test(alarm): add SoundRef with a never-throwing decoder"
```

---

### Task 2: RingSettings fields, legacy migration and override writes

**Files:**
- Modify: `app/src/main/java/com/replydev/reveille/data/SettingsStore.kt`
- Modify: `app/src/test/java/com/replydev/reveille/data/StoresTest.kt` (the `settingsHaveDefaultsAndUpdate` test uses `ringtoneUri`)

- [ ] **Step 1: Write the failing tests.** Replace `settingsHaveDefaultsAndUpdate` and append the new tests to `StoresTest`:

```kotlin
    @Test
    fun settingsHaveDefaultsAndUpdate() = runBlocking {
        val settings = SettingsStore(store())
        assertEquals(RingSettings(), settings.current())
        settings.update { it.copy(snoozeMinutes = 5, vibrate = false, sound = SoundRef.SystemTone("content://x")) }
        assertEquals(RingSettings(sound = SoundRef.SystemTone("content://x"), vibrate = false, snoozeMinutes = 5), settings.current())
        settings.update { it.copy(sound = SoundRef.SystemDefault) }
        assertEquals(SoundRef.SystemDefault, settings.current().sound)
    }

    @Test
    fun legacyRingtoneUriIsReadAsASystemToneAndDroppedOnWrite() = runBlocking {
        val prefs = store()
        prefs.edit { it[stringPreferencesKey("ringtone_uri")] = "content://legacy" }
        val settings = SettingsStore(prefs)
        assertEquals(SoundRef.SystemTone("content://legacy"), settings.current().sound)
        settings.update { it.copy(vibrate = false) }
        assertNull(prefs.data.first()[stringPreferencesKey("ringtone_uri")])
        assertEquals(SoundRef.SystemTone("content://legacy"), settings.current().sound)
    }

    @Test
    fun soundOverridesAreSetClearedAndPruned() = runBlocking {
        val settings = SettingsStore(store())
        val tone = SoundRef.SystemTone("content://a=b") // '=' inside the value must survive
        settings.setPolicySound(1, tone, livePolicyIds = setOf(1, 2))
        settings.setPolicySound(2, SoundRef.Imported("x"), livePolicyIds = setOf(1, 2))
        settings.setPolicySound(1, null, livePolicyIds = setOf(1)) // policy 2 deleted meanwhile
        assertEquals(emptyMap<Long, SoundRef>(), settings.current().policySounds)

        settings.setOneOffSound(7, tone, liveOneOffIds = setOf(7, 8))
        settings.setOneOffSound(8, SoundRef.SystemDefault, liveOneOffIds = setOf(7, 8))
        settings.setOneOffSound(9, SoundRef.Imported("y"), liveOneOffIds = setOf(8, 9)) // 7 gone
        assertEquals(mapOf(8L to SoundRef.SystemDefault, 9L to SoundRef.Imported("y")), settings.current().oneOffSounds)
    }

    @Test
    fun malformedOverrideEntriesAreSkipped() = runBlocking {
        val prefs = store()
        prefs.edit { it[stringSetPreferencesKey("policy_sounds")] = setOf("nope", "3=file:z", "=tone:q") }
        assertEquals(mapOf(3L to SoundRef.Imported("z")), SettingsStore(prefs).current().policySounds)
    }

    @Test
    fun importedSoundsAddAndRemoveResettingEveryUse() = runBlocking {
        val settings = SettingsStore(store())
        settings.addImportedSound(ImportedSound("a", "Birds = morning"))
        settings.addImportedSound(ImportedSound("b", "Bells"))
        settings.update { it.copy(sound = SoundRef.Imported("a")) }
        settings.setPolicySound(1, SoundRef.Imported("a"), setOf(1, 2))
        settings.setPolicySound(2, SoundRef.Imported("b"), setOf(1, 2))
        settings.setOneOffSound(5, SoundRef.Imported("a"), setOf(5))
        assertEquals(3, settings.current().usesOf("a"))

        settings.removeImportedSound("a")
        val now = settings.current()
        assertEquals(listOf(ImportedSound("b", "Bells")), now.importedSounds)
        assertEquals(SoundRef.SystemDefault, now.sound)
        assertEquals(mapOf(1L to SoundRef.SystemDefault, 2L to SoundRef.Imported("b")), now.policySounds)
        assertEquals(mapOf(5L to SoundRef.SystemDefault), now.oneOffSounds)
    }
```

Add these imports to `StoresTest`: `com.replydev.reveille.alarm.SoundRef`.

- [ ] **Step 2: Run them and check that they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*StoresTest'`
Expected: compilation FAILS (unresolved `sound`, `setPolicySound`, `ImportedSound`, ...).

- [ ] **Step 3: Implement.** In `SettingsStore.kt`:

Replace `val ringtoneUri: String? = null,` in `RingSettings` with:

```kotlin
    /** The default alarm sound. Replaces the old `ringtone_uri` setting, which is read as a [SoundRef.SystemTone]. */
    val sound: SoundRef = SoundRef.SystemDefault,
```

and append to `RingSettings` (after `policyRingThemes`):

```kotlin
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
```

(Remove the original closing `)` of `RingSettings` so the class ends with the body above.)

In `update`, replace the `RINGTONE` line with:

```kotlin
            prefs.remove(LEGACY_RINGTONE)
            prefs[SOUND] = next.sound.encode()
            prefs[POLICY_SOUNDS] = next.policySounds.map { (id, ref) -> "$id=${ref.encode()}" }.toSet()
            prefs[ONE_OFF_SOUNDS] = next.oneOffSounds.map { (id, ref) -> "$id=${ref.encode()}" }.toSet()
            prefs[IMPORTED_SOUNDS] = next.importedSounds.map { "${it.id}=${it.name}" }.toSet()
```

Add these methods after `setPolicyRingTheme`:

```kotlin
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
```

In `read`, replace `ringtoneUri = prefs[RINGTONE],` with:

```kotlin
            sound = prefs[SOUND]?.let(SoundRef::decode) ?: prefs[LEGACY_RINGTONE]?.let(SoundRef::SystemTone) ?: SoundRef.SystemDefault,
```

and add after `policyRingThemes = ...`:

```kotlin
            policySounds = parseSoundOverrides(prefs[POLICY_SOUNDS].orEmpty()),
            oneOffSounds = parseSoundOverrides(prefs[ONE_OFF_SOUNDS].orEmpty()),
            importedSounds = prefs[IMPORTED_SOUNDS].orEmpty().mapNotNull { entry ->
                val id = entry.substringBefore('=', "").ifEmpty { return@mapNotNull null }
                ImportedSound(id, entry.substringAfter('='))
            }.sortedBy { it.name.lowercase() },
```

Add next to `parseOverrides`:

```kotlin
    /** "id=<encoded ref>" entries, split on the first '='; a malformed entry is skipped. */
    private fun parseSoundOverrides(entries: Set<String>): Map<Long, SoundRef> = entries.mapNotNull { entry ->
        val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
        id to SoundRef.decode(entry.substringAfter('='))
    }.toMap()
```

In the companion, rename `RINGTONE` to `LEGACY_RINGTONE` (still `"ringtone_uri"`) and add:

```kotlin
        val SOUND = stringPreferencesKey("sound")
        val POLICY_SOUNDS = stringSetPreferencesKey("policy_sounds")
        val ONE_OFF_SOUNDS = stringSetPreferencesKey("one_off_sounds")
        val IMPORTED_SOUNDS = stringSetPreferencesKey("imported_sounds")
```

Import `com.replydev.reveille.alarm.SoundRef`.

- [ ] **Step 4: Fix the callers so the app compiles.**
  - `RingingService.kt:291`: replace `settings.ringtoneUri?.let(Uri::parse)` with `(settings.sound as? SoundRef.SystemTone)?.uri?.let(Uri::parse)`. This is temporary; Task 4 replaces it.
  - `SettingsScreen.kt`: change `ringtoneUri = uri?.toString()` to `sound = uri?.let { SoundRef.SystemTone(it.toString()) } ?: SoundRef.SystemDefault`, and change `current.ringtoneUri` (2 uses) to `(current.sound as? SoundRef.SystemTone)?.uri`. Task 7 rewrites this row anyway.

- [ ] **Step 5: Run the tests and check that they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*StoresTest' --tests '*SoundRefTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(data): store the alarm sound as a SoundRef with per-policy and per-one-off overrides"
```

---

### Task 3: resolveSound, soundCandidates and prepareFirst

**Files:**
- Modify: `app/src/main/java/com/replydev/reveille/alarm/SoundRef.kt`
- Test: `app/src/test/java/com/replydev/reveille/alarm/SoundResolutionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.replydev.reveille.alarm

import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SoundResolutionTest {
    private val at = Instant.parse("2026-11-12T06:30:00Z")
    private val global = SoundRef.SystemTone("content://global")
    private val forPolicy = SoundRef.Imported("p")
    private val forOneOff = SoundRef.Imported("o")
    private val settings = RingSettings(sound = global, policySounds = mapOf(1L to forPolicy), oneOffSounds = mapOf(5L to forOneOff))

    private fun armed(key: String, vararg sources: String) = Armed(key, at, "x", sources.toList())

    @Test
    fun policyAlarmUsesItsOverride() = assertEquals(forPolicy, resolveSound(armed("P:1:3:2026-11-12", "P:1:3:2026-11-12"), settings))

    @Test
    fun policyWithoutOverrideUsesTheGlobalSound() = assertEquals(global, resolveSound(armed("P:2:3:2026-11-12"), settings))

    @Test
    fun oneOffUsesItsOverride() = assertEquals(forOneOff, resolveSound(armed("O:5", "O:5"), settings))

    @Test
    fun oneOffWithoutOverrideUsesTheGlobalSound() = assertEquals(global, resolveSound(armed("O:6"), settings))

    @Test
    fun mergedOccurrencePrefersTheOneOff() =
        assertEquals(forOneOff, resolveSound(armed("P:1:3:2026-11-12", "P:1:3:2026-11-12", "O:5"), settings))

    @Test
    fun snoozesKeepTheirAlarmsSound() {
        assertEquals(forPolicy, resolveSound(Armed.snooze(at, "x", listOf("P:1:3:2026-11-12")), settings))
        assertEquals(forOneOff, resolveSound(Armed.snooze(at, "x", listOf("O:5")), settings))
    }

    @Test
    fun testAlarmAndUnknownKeysUseTheGlobalSound() {
        assertEquals(global, resolveSound(armed("T:1"), settings))
        assertEquals(global, resolveSound(armed("unknown"), settings))
    }

    @Test
    fun candidatesAlwaysEndWithSystemDefaultThenBundled() {
        val path = { id: String -> "/de/files/sounds/$id" }
        assertEquals(
            listOf(SoundSource.File("/de/files/sounds/p"), SoundSource.SystemDefault, SoundSource.Bundled),
            soundCandidates(forPolicy, path),
        )
        assertEquals(
            listOf(SoundSource.Uri("content://global"), SoundSource.SystemDefault, SoundSource.Bundled),
            soundCandidates(global, path),
        )
        assertEquals(listOf(SoundSource.SystemDefault, SoundSource.Bundled), soundCandidates(SoundRef.SystemDefault, path))
    }

    @Test
    fun prepareFirstFallsThroughFailures() {
        val failed = mutableListOf<SoundSource>()
        val used = prepareFirst(
            listOf(SoundSource.File("/missing"), SoundSource.SystemDefault, SoundSource.Bundled),
            attempt = { if (it !is SoundSource.Bundled) throw IOException("no") },
            onFailure = { source, _ -> failed += source },
        )
        assertEquals(SoundSource.Bundled, used)
        assertEquals(listOf(SoundSource.File("/missing"), SoundSource.SystemDefault), failed)
    }

    @Test
    fun prepareFirstThrowsTheLastFailureWhenNothingWorks() {
        assertThrows(IOException::class.java) {
            prepareFirst(listOf(SoundSource.SystemDefault, SoundSource.Bundled), attempt = { throw IOException("no") }, onFailure = { _, _ -> })
        }
    }
}
```

`Armed.snooze(at, label, sourceKeys)` takes the sources as its third parameter (checked).

- [ ] **Step 2: Run it and check that it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundResolutionTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement.** Append to `SoundRef.kt`:

```kotlin
/**
 * The sound for [armed]: the override of a one-off among its sources (one-offs win in a merged occurrence: they were
 * set for that alarm), else its policy's override, else the default. A snooze carries its alarm's sources.
 */
fun resolveSound(armed: Armed, settings: RingSettings): SoundRef {
    val oneOff = (listOf(armed.key) + armed.sourceKeys).firstNotNullOfOrNull { key ->
        (Source.parse(key) as? Source.OneOffAlarm)?.let { settings.oneOffSounds[it.oneOffId] }
    }
    return oneOff ?: ringPolicyId(armed)?.let(settings.policySounds::get) ?: settings.sound
}

/** Something MediaPlayer can be pointed at. */
sealed interface SoundSource {
    data class File(val path: String) : SoundSource
    data class Uri(val uri: String) : SoundSource
    data object SystemDefault : SoundSource
    /** R.raw.fallback_alarm: always readable, even before the first unlock. */
    data object Bundled : SoundSource
}

/** What to try, in order: the chosen sound, the system default alarm, then the bundled sound. */
fun soundCandidates(ref: SoundRef, importedPath: (String) -> String): List<SoundSource> = buildList {
    when (ref) {
        is SoundRef.Imported -> add(SoundSource.File(importedPath(ref.id)))
        is SoundRef.SystemTone -> add(SoundSource.Uri(ref.uri))
        SoundRef.SystemDefault -> Unit
    }
    add(SoundSource.SystemDefault)
    add(SoundSource.Bundled)
}

/** Runs [attempt] on each candidate until one succeeds and returns it; if none does, throws the last failure. */
fun prepareFirst(candidates: List<SoundSource>, attempt: (SoundSource) -> Unit, onFailure: (SoundSource, Exception) -> Unit): SoundSource {
    var last: Exception? = null
    for (source in candidates) {
        try {
            attempt(source)
            return source
        } catch (e: Exception) {
            onFailure(source, e)
            last = e
        }
    }
    throw last ?: IllegalStateException("No sound candidates")
}
```

Add these imports: `com.replydev.reveille.data.RingSettings`, `com.replydev.reveille.engine.Armed`, `com.replydev.reveille.engine.Source`.

- [ ] **Step 4: Run it and check that it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundResolutionTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/replydev/reveille/alarm/SoundRef.kt app/src/test/java/com/replydev/reveille/alarm/SoundResolutionTest.kt
git commit -m "test(alarm): resolve an alarm's sound and its fallback order"
```

---

### Task 4: SoundLibrary

**Files:**
- Create: `app/src/main/java/com/replydev/reveille/data/SoundLibrary.kt`
- Test: `app/src/test/java/com/replydev/reveille/data/SoundLibraryTest.kt`

- [ ] **Step 1: Write the failing test** (plain JVM, with no Android calls on these paths):

```kotlin
package com.replydev.reveille.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.replydev.reveille.alarm.SoundRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Robolectric only for android.util.Log; the probe is faked, so no MediaPlayer runs. */
@RunWith(AndroidJUnit4::class)
class SoundLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }) }
    private val dir by lazy { File(tmp.root, "sounds") }
    private fun library(probe: (File) -> Boolean = { true }, maxBytes: Long = 1_000) = SoundLibrary(dir, settings, probe, maxBytes)
    private fun bytes(n: Int) = { ByteArrayInputStream(ByteArray(n)) as InputStream }

    @Test
    fun importCopiesTheFileAndListsItWithoutExtension() = runBlocking {
        val result = library().import("Morning birds.mp3", declaredSize = 10, open = bytes(10))
        val sound = (result as ImportResult.Imported).sound
        assertEquals("Morning birds", sound.name)
        assertEquals(10L, library().file(sound.id).length())
        assertEquals(listOf(sound), settings.current().importedSounds)
        assertEquals(listOf(sound.id), dir.list()!!.toList())
    }

    @Test
    fun tooLargeIsRejectedByDeclaredOrActualSize() = runBlocking {
        assertEquals(ImportResult.TooLarge, library().import("a.mp3", declaredSize = 2_000, open = bytes(10)))
        assertEquals(ImportResult.TooLarge, library().import("a.mp3", declaredSize = null, open = bytes(2_000)))
        assertTrue(dir.list().orEmpty().isEmpty())
        assertTrue(settings.current().importedSounds.isEmpty())
    }

    @Test
    fun notAudioIsRejected() = runBlocking {
        assertEquals(ImportResult.NotAudio, library(probe = { false }).import("notes.txt", 10, bytes(10)))
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun unreadableIsRejected() = runBlocking {
        assertEquals(ImportResult.Unreadable, library().import("a.mp3", 10) { null })
        assertEquals(ImportResult.Unreadable, library().import("a.mp3", 10) { throw IOException("gone") })
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun deleteResetsUsesBeforeRemovingTheFile() = runBlocking {
        val sound = (library().import("a.mp3", 10, bytes(10)) as ImportResult.Imported).sound
        settings.update { it.copy(sound = SoundRef.Imported(sound.id)) }
        library().delete(sound.id)
        assertEquals(SoundRef.SystemDefault, settings.current().sound)
        assertTrue(settings.current().importedSounds.isEmpty())
        assertFalse(library().file(sound.id).exists())
    }

    @Test
    fun sweepRemovesUnlistedFilesAndTempFiles() = runBlocking {
        val kept = (library().import("a.mp3", 10, bytes(10)) as ImportResult.Imported).sound
        File(dir, "orphan").writeText("x")
        File(dir, "half.tmp").writeText("x")
        library().sweepOrphans()
        assertEquals(listOf(kept.id), dir.list()!!.toList())
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundLibraryTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

```kotlin
package com.replydev.reveille.data

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

sealed interface ImportResult {
    data class Imported(val sound: ImportedSound) : ImportResult
    data object TooLarge : ImportResult
    data object NotAudio : ImportResult
    data object Unreadable : ImportResult
}

/**
 * Imported alarm sounds: private copies in device-protected [dir], so they ring before the first unlock and survive the
 * original being moved. The alarm path only calls [file]; everything else runs from the UI or at app start.
 */
class SoundLibrary(
    private val dir: File,
    private val settings: SettingsStore,
    private val probe: (File) -> Boolean = ::canPlay,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = Mutex()

    fun file(id: String): File = File(dir, id)

    suspend fun importUri(resolver: ContentResolver, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val (name, size) = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
            }
        }.getOrNull() ?: (null to null)
        import(name ?: uri.lastPathSegment ?: "Sound", size) { resolver.openInputStream(uri) }
    }

    suspend fun import(displayName: String, declaredSize: Long?, open: () -> InputStream?): ImportResult = withContext(Dispatchers.IO) {
        if (declaredSize != null && declaredSize > maxBytes) return@withContext ImportResult.TooLarge
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val tmp = File(dir, "$id.tmp")
            try {
                dir.mkdirs()
                val input = open() ?: return@withLock ImportResult.Unreadable
                val copied = input.use { source -> tmp.outputStream().use { copyLimited(source, it) } }
                if (copied == null) return@withLock ImportResult.TooLarge
                if (!probe(tmp)) return@withLock ImportResult.NotAudio
                val target = file(id)
                if (!tmp.renameTo(target)) return@withLock ImportResult.Unreadable
                val sound = ImportedSound(id, displayName.substringBeforeLast('.').ifBlank { displayName })
                try {
                    settings.addImportedSound(sound)
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                ImportResult.Imported(sound)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot import $displayName", e)
                ImportResult.Unreadable
            } finally {
                tmp.delete()
            }
        }
    }

    /** Points every use back to the default first; only then removes the file. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            settings.removeImportedSound(id)
            file(id).delete()
        }
    }

    /** Removes files no entry refers to (a crash mid-import or mid-delete). Never runs on the alarm path. */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        lock.withLock {
            val listed = settings.current().importedSounds.map { it.id }.toSet()
            dir.listFiles().orEmpty().filter { it.name !in listed }.forEach { it.delete() }
        }
    }

    /** Copies at most [maxBytes]; returns the byte count, or null if the source is larger. */
    private fun copyLimited(source: InputStream, sink: java.io.OutputStream): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) return total
            total += n
            if (total > maxBytes) return null
            sink.write(buffer, 0, n)
        }
    }

    companion object {
        private const val TAG = "SoundLibrary"
        const val MAX_BYTES = 20L * 1024 * 1024

        private fun canPlay(file: File): Boolean {
            val player = MediaPlayer()
            return try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                player.setDataSource(file.path)
                player.prepare()
                player.duration > 0
            } catch (e: Exception) {
                false
            } finally {
                player.release()
            }
        }
    }
}
```

Note: `ImportResult.TooLarge` from the `copied == null` branch must still clean up. The `finally` deletes `tmp`, which covers it.

- [ ] **Step 4: Run it and check that it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SoundLibraryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/replydev/reveille/data/SoundLibrary.kt app/src/test/java/com/replydev/reveille/data/SoundLibraryTest.kt
git commit -m "test(data): import, delete and sweep alarm sounds in device-protected storage"
```

---

### Task 5: Wire the library and play the resolved sound

**Files:**
- Modify: `app/src/main/java/com/replydev/reveille/AppContainer.kt`
- Modify: `app/src/main/java/com/replydev/reveille/ReveilleApp.kt`
- Modify: `app/src/main/java/com/replydev/reveille/alarm/RingingService.kt` (`startSound` ~243, `prepare` ~284)
- Test: `app/src/test/java/com/replydev/reveille/alarm/RingingServiceTest.kt`

- [ ] **Step 1: Write the failing test.** Append to `RingingServiceTest`. It proves that a missing imported file still rings through the chain. Under Robolectric `MediaPlayer` can't play any source, so the service posts the fallback notification. The test asserts the occurrence is FIRED and the alarm keeps ringing, which is the same guarantee the existing tests check:

```kotlin
    @Test
    fun missingImportedSoundStillRings() {
        read { app.container.settings.update { it.copy(sound = SoundRef.Imported("deleted-file")) } }
        ring(armed)
        awaitUntil { RingingService.ringing.value != null && read { app.container.schedulerState.guard() } != null }
        assertEquals(FireOutcome.FIRED, outcome(armed))
        assertEquals(armed.key, RingingService.ringing.value?.armed?.key)
    }
```

Add the import `com.replydev.reveille.alarm.SoundRef` (same package, so none is needed).

- [ ] **Step 2: Run it.** `./gradlew :app:testDebugUnitTest --tests '*RingingServiceTest'`. Expected: compilation FAILS only if something is missing. Otherwise it may already pass, which is fine: it is a regression guard for the next step.

- [ ] **Step 3: Implement.**

`AppContainer.kt`, after `settings`:

```kotlin
    /** Imported alarm sounds, in device-protected storage: the alarm path reads them before the first unlock. */
    val sounds: SoundLibrary by lazy { SoundLibrary(java.io.File(deviceStorage.filesDir, "sounds"), settings) }
```

(Import `com.replydev.reveille.data.SoundLibrary`.)

`ReveilleApp.onCreate`, after `createChannels()`:

```kotlin
        // Housekeeping only, never on the alarm path; DataStore is device-protected, so this is safe in Direct Boot too,
        // but waiting for the unlock keeps a locked-boot process doing nothing but ringing.
        if (getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true) {
            container.appScope.launch { runCatching { container.sounds.sweepOrphans() } }
        }
```

(Import `kotlinx.coroutines.launch`.)

`RingingService.startSound`: change the `prepare(it, settings)` call to `prepare(it, settings, ringing.armed)`.

Replace `prepare` with:

```kotlin
    /** Prepares the first sound that works: the alarm's resolved sound, the system default, then the bundled one. */
    private fun prepare(player: MediaPlayer, settings: RingSettings, armed: Armed) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = soundCandidates(resolveSound(armed, settings)) { id -> container.sounds.file(id).path }
        prepareFirst(
            candidates,
            attempt = { source ->
                player.reset()
                player.setAudioAttributes(attributes)
                when (source) {
                    is SoundSource.File -> player.setDataSource(source.path)
                    is SoundSource.Uri -> player.setDataSource(this, Uri.parse(source.uri))
                    // The settings indirection (content://settings/system/alarm_alert), resolved at play time.
                    SoundSource.SystemDefault -> player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    // Media providers are unavailable before the first unlock; the bundled sound always works.
                    SoundSource.Bundled -> resources.openRawResourceFd(R.raw.fallback_alarm).use { afd ->
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
                player.prepare()
            },
            onFailure = { source, e -> Log.w(TAG, "Cannot play $source for ${armed.key}; trying the next sound", e) },
        )
    }
```

If every candidate fails, `prepareFirst` throws. The existing `catch (e: Exception)` in `startSound` then posts the fallback alarm, which is unchanged behaviour.

- [ ] **Step 4: Run all app tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all of them, including the existing `RingingServiceTest` cases at sdk 31/33/35).

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(alarm): ring with the resolved sound, falling back to the system default and the bundled sound"
```

---

### Task 6: Strings and icons

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `values-it`, `values-es`, `values-fr`, `values-de`
- Create: `app/src/main/res/drawable/ic_play.xml`, `ic_stop.xml`, `ic_upload.xml`

- [ ] **Step 1: Add the English strings** after `settings_ringing_theme`:

```xml
    <string name="sound_default_group">Default sound</string>
    <string name="sound_system_default">System default alarm</string>
    <string name="sound_system_tone">System tone</string>
    <string name="sound_system_tone_pick">Choose a system tone</string>
    <string name="sound_import">Import audio file…</string>
    <string name="sound_importing">Importing…</string>
    <string name="sound_imported">Imported %1$s</string>
    <string name="sound_import_too_large">That file is too large (20 MB max).</string>
    <string name="sound_import_not_audio">That file isn\'t a playable audio file.</string>
    <string name="sound_import_unreadable">Couldn\'t read that file.</string>
    <string name="sound_play">Play %1$s</string>
    <string name="sound_stop">Stop</string>
    <string name="sound_delete">Delete %1$s</string>
    <string name="sound_delete_title">Delete sound?</string>
    <string name="sound_delete_unused">%1$s will be removed from Reveille.</string>
    <plurals name="sound_delete_in_use">
        <item quantity="one">%1$s is used by %2$d alarm choice. It will go back to the default sound.</item>
        <item quantity="other">%1$s is used by %2$d alarm choices. They will go back to the default sound.</item>
    </plurals>
    <string name="sound_per_policy">Per policy</string>
    <string name="sound_follow_default">Default</string>
    <string name="sound_save_failed">Could not save the sound.</string>
    <string name="sound_preview_failed">Couldn\'t play that sound.</string>
    <string name="oneoff_sound">Sound</string>
    <string name="oneoff_sound_save_failed">Alarm saved, but its sound could not be saved.</string>
```

- [ ] **Step 2: Add the translations.** Use the same keys in each locale file. it/es/fr plurals need `one`, `many` and `other` (`many` = `other` text); de needs `one` and `other`.

Italian (`values-it`):
```xml
    <string name="sound_default_group">Suono predefinito</string>
    <string name="sound_system_default">Sveglia predefinita di sistema</string>
    <string name="sound_system_tone">Suoneria di sistema</string>
    <string name="sound_system_tone_pick">Scegli una suoneria di sistema</string>
    <string name="sound_import">Importa file audio…</string>
    <string name="sound_importing">Importazione…</string>
    <string name="sound_imported">%1$s importato</string>
    <string name="sound_import_too_large">Il file è troppo grande (massimo 20 MB).</string>
    <string name="sound_import_not_audio">Il file non è un audio riproducibile.</string>
    <string name="sound_import_unreadable">Impossibile leggere il file.</string>
    <string name="sound_play">Riproduci %1$s</string>
    <string name="sound_stop">Interrompi</string>
    <string name="sound_delete">Elimina %1$s</string>
    <string name="sound_delete_title">Eliminare il suono?</string>
    <string name="sound_delete_unused">%1$s verrà rimosso da Reveille.</string>
    <plurals name="sound_delete_in_use">
        <item quantity="one">%1$s è usato da %2$d scelta. Tornerà al suono predefinito.</item>
        <item quantity="many">%1$s è usato da %2$d scelte. Torneranno al suono predefinito.</item>
        <item quantity="other">%1$s è usato da %2$d scelte. Torneranno al suono predefinito.</item>
    </plurals>
    <string name="sound_per_policy">Per programma</string>
    <string name="sound_follow_default">Predefinito</string>
    <string name="sound_save_failed">Impossibile salvare il suono.</string>
    <string name="sound_preview_failed">Impossibile riprodurre il suono.</string>
    <string name="oneoff_sound">Suono</string>
    <string name="oneoff_sound_save_failed">Sveglia salvata, ma non il suo suono.</string>
```

`sound_per_policy` uses each locale's existing word for "policy" from `ring_theme_per_policy`.

Spanish (`values-es`):
```xml
    <string name="sound_default_group">Sonido predeterminado</string>
    <string name="sound_system_default">Alarma predeterminada del sistema</string>
    <string name="sound_system_tone">Tono del sistema</string>
    <string name="sound_system_tone_pick">Elegir un tono del sistema</string>
    <string name="sound_import">Importar archivo de audio…</string>
    <string name="sound_importing">Importando…</string>
    <string name="sound_imported">%1$s importado</string>
    <string name="sound_import_too_large">El archivo es demasiado grande (máximo 20 MB).</string>
    <string name="sound_import_not_audio">El archivo no es un audio reproducible.</string>
    <string name="sound_import_unreadable">No se pudo leer el archivo.</string>
    <string name="sound_play">Reproducir %1$s</string>
    <string name="sound_stop">Detener</string>
    <string name="sound_delete">Eliminar %1$s</string>
    <string name="sound_delete_title">¿Eliminar el sonido?</string>
    <string name="sound_delete_unused">%1$s se quitará de Reveille.</string>
    <plurals name="sound_delete_in_use">
        <item quantity="one">%1$s se usa en %2$d elección. Volverá al sonido predeterminado.</item>
        <item quantity="many">%1$s se usa en %2$d elecciones. Volverán al sonido predeterminado.</item>
        <item quantity="other">%1$s se usa en %2$d elecciones. Volverán al sonido predeterminado.</item>
    </plurals>
    <string name="sound_per_policy">Por programa</string>
    <string name="sound_follow_default">Predeterminado</string>
    <string name="sound_save_failed">No se pudo guardar el sonido.</string>
    <string name="sound_preview_failed">No se pudo reproducir el sonido.</string>
    <string name="oneoff_sound">Sonido</string>
    <string name="oneoff_sound_save_failed">Alarma guardada, pero no se pudo guardar su sonido.</string>
```

French (`values-fr`):
```xml
    <string name="sound_default_group">Son par défaut</string>
    <string name="sound_system_default">Alarme par défaut du système</string>
    <string name="sound_system_tone">Sonnerie du système</string>
    <string name="sound_system_tone_pick">Choisir une sonnerie du système</string>
    <string name="sound_import">Importer un fichier audio…</string>
    <string name="sound_importing">Importation…</string>
    <string name="sound_imported">%1$s importé</string>
    <string name="sound_import_too_large">Ce fichier est trop volumineux (20 Mo max).</string>
    <string name="sound_import_not_audio">Ce fichier n\'est pas un audio lisible.</string>
    <string name="sound_import_unreadable">Impossible de lire ce fichier.</string>
    <string name="sound_play">Écouter %1$s</string>
    <string name="sound_stop">Arrêter</string>
    <string name="sound_delete">Supprimer %1$s</string>
    <string name="sound_delete_title">Supprimer le son ?</string>
    <string name="sound_delete_unused">%1$s sera retiré de Reveille.</string>
    <plurals name="sound_delete_in_use">
        <item quantity="one">%1$s est utilisé par %2$d choix. Il reviendra au son par défaut.</item>
        <item quantity="many">%1$s est utilisé par %2$d choix. Ils reviendront au son par défaut.</item>
        <item quantity="other">%1$s est utilisé par %2$d choix. Ils reviendront au son par défaut.</item>
    </plurals>
    <string name="sound_per_policy">Par programme</string>
    <string name="sound_follow_default">Par défaut</string>
    <string name="sound_save_failed">Impossible d\'enregistrer le son.</string>
    <string name="sound_preview_failed">Impossible de lire ce son.</string>
    <string name="oneoff_sound">Son</string>
    <string name="oneoff_sound_save_failed">Alarme enregistrée, mais pas son son.</string>
```

German (`values-de`):
```xml
    <string name="sound_default_group">Standardton</string>
    <string name="sound_system_default">Standard-Weckton des Systems</string>
    <string name="sound_system_tone">Systemton</string>
    <string name="sound_system_tone_pick">Systemton auswählen</string>
    <string name="sound_import">Audiodatei importieren…</string>
    <string name="sound_importing">Wird importiert…</string>
    <string name="sound_imported">%1$s importiert</string>
    <string name="sound_import_too_large">Die Datei ist zu groß (max. 20 MB).</string>
    <string name="sound_import_not_audio">Die Datei ist keine abspielbare Audiodatei.</string>
    <string name="sound_import_unreadable">Die Datei konnte nicht gelesen werden.</string>
    <string name="sound_play">%1$s abspielen</string>
    <string name="sound_stop">Stopp</string>
    <string name="sound_delete">%1$s löschen</string>
    <string name="sound_delete_title">Ton löschen?</string>
    <string name="sound_delete_unused">%1$s wird aus Reveille entfernt.</string>
    <plurals name="sound_delete_in_use">
        <item quantity="one">%1$s wird von %2$d Auswahl verwendet. Sie kehrt zum Standardton zurück.</item>
        <item quantity="other">%1$s wird von %2$d Auswahlen verwendet. Sie kehren zum Standardton zurück.</item>
    </plurals>
    <string name="sound_per_policy">Pro Zeitplan</string>
    <string name="sound_follow_default">Standard</string>
    <string name="sound_save_failed">Der Ton konnte nicht gespeichert werden.</string>
    <string name="sound_preview_failed">Der Ton konnte nicht abgespielt werden.</string>
    <string name="oneoff_sound">Ton</string>
    <string name="oneoff_sound_save_failed">Wecker gespeichert, sein Ton jedoch nicht.</string>
```

- [ ] **Step 3: Add the icons.** Follow `ic_music.xml`'s style: 24 dp, 2 dp round white stroke.

`ic_play.xml` path: `M7,4.5v15l12,-7.5z` with `android:fillColor="#FFFFFFFF"`, plus the same as a stroke with `strokeLineJoin="round"`.
`ic_stop.xml` path: `M6,6h12v12h-12z`, filled and stroked the same way.
`ic_upload.xml` paths: `M12,15V3`, `M7,8l5,-5l5,5`, `M4,15v4a2,2 0,0 0,2,2h12a2,2 0,0 0,2,-2v-4` (stroke only).

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests '*TranslationsTest' :app:lintDebug`
Expected: PASS, with 0 lint errors. `UnusedResources` warnings are fine until Tasks 7–9 use the strings.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res
git commit -m "feat(i18n): strings and icons for custom alarm sounds"
```

---

### Task 7: Shared sound UI pieces

**Files:**
- Create: `app/src/main/java/com/replydev/reveille/ui/settings/SoundUi.kt`

These are UI glue with no logic worth unit-testing beyond what Tasks 1–4 cover. They are checked by the Maestro flow in Task 10.

- [ ] **Step 1: Implement**

```kotlin
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
    runCatching { android.media.RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull()
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
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/replydev/reveille/ui/settings/SoundUi.kt
git commit -m "feat(ui): shared sound picker, names and preview"
```

---

### Task 8: Alarm sound screen, route and Settings row

**Files:**
- Create: `app/src/main/java/com/replydev/reveille/ui/settings/AlarmSoundScreen.kt`
- Modify: `app/src/main/java/com/replydev/reveille/ui/ReveilleNavHost.kt:51-63,126-127`
- Modify: `app/src/main/java/com/replydev/reveille/ui/settings/SettingsScreen.kt` (picker launcher at lines 84-91, ringtone name at 96-99, Alarm sound row at 104-120, `ringtoneTitle` at 255-259)

- [ ] **Step 1: Implement the screen**

```kotlin
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
import com.replydev.reveille.data.ImportedSound
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
    val res = context.resources
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
            res.getString(R.string.sound_import_unreadable),
            guarded = false,
            // importUri reports every failure as a result; the finally covers cancellation.
            action = { try { container.sounds.importUri(context.contentResolver, uri) } finally { importing = false } },
            onSuccess = { result ->
                val text = when (result) {
                    is ImportResult.Imported -> res.getString(R.string.sound_imported, result.sound.name)
                    ImportResult.TooLarge -> res.getString(R.string.sound_import_too_large)
                    ImportResult.NotAudio -> res.getString(R.string.sound_import_not_audio)
                    ImportResult.Unreadable -> res.getString(R.string.sound_import_unreadable)
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
                    RadioRow(stringResource(R.string.sound_system_default), null, current.sound == SoundRef.SystemDefault, divider = false) {
                        setDefault(SoundRef.SystemDefault)
                    }
                    val tone = current.sound as? SoundRef.SystemTone
                    RadioRow(
                        stringResource(R.string.sound_system_tone),
                        tone?.let { rememberSoundName(it, current.importedSounds) } ?: stringResource(R.string.sound_system_tone_pick),
                        tone != null,
                    ) {
                        toneTarget = null
                        pickTone(tone?.uri)
                    }
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
```

Signatures this relies on (checked): `ScreenSession.write(failure, guarded = true, action, onSuccess = {})` has no failure callback, so the `finally` in `action` resets `importing`. `IconCircleButton(icon, contentDescription, onClick, modifier, size = 40.dp, iconSize, container, tint, enabled)`.

- [ ] **Step 2: Add the route.** In `ReveilleNavHost.kt`, add `const val ALARM_SOUND = "settings/alarm-sound"` to `Routes`, then:

```kotlin
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenUpdate = { nav.openUpdate() },
                    onOpenRingTheme = { nav.navigate(Routes.RINGING_THEME) },
                    onOpenAlarmSound = { nav.navigate(Routes.ALARM_SOUND) },
                )
            }
            composable(Routes.ALARM_SOUND) { entry -> AlarmSoundScreen(onBack = { nav.leave(entry) }) }
```

Import `com.replydev.reveille.ui.settings.AlarmSoundScreen`.

- [ ] **Step 3: Update the Settings row.** In `SettingsScreen.kt`:
  - Add the parameter `onOpenAlarmSound: () -> Unit`.
  - Delete the `picker` launcher, the `noSoundPicker` val, the `ringtoneName` `produceState` block and `ringtoneTitle`.
  - In the Alarm sound row, replace the `.clickable(onClickLabel = ...) { ... }` body with `.clickable(onClickLabel = stringResource(R.string.settings_choose_sound), onClick = onOpenAlarmSound)`.
  - Replace `Text(ringtoneName, ...)` with `Text(rememberSoundName(current.sound, current.importedSounds), style = type.caption, color = colors.muted)`.
  - Remove the imports that are now unused. Lint fails on them only as warnings, but keep it clean.

- [ ] **Step 4: Build and lint**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: all PASS, 0 lint errors.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main
git commit -m "feat(ui): Alarm sound screen with imported sounds and per-policy overrides"
```

---

### Task 9: Sound row in the one-off sheet

**Files:**
- Modify: `app/src/main/java/com/replydev/reveille/ui/oneoff/OneOffEditorScreen.kt`

- [ ] **Step 1: Implement.** In `OneOffEditorScreen`:

State, next to `replaceDay`:

```kotlin
    // The sound override, encoded (null: Default). Saved with the alarm.
    var soundCode by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSound by rememberSaveable { mutableStateOf(false) }
    val ringSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
```

In the `LaunchedEffect` loader, inside `if (existing != null) { ... }`, add:

```kotlin
            soundCode = container.settings.current().oneOffSounds[existing.id]?.encode()
```

In `save`, replace `action = { repository.saveOneOff(entity) },` and its `onSuccess` with:

```kotlin
                action = {
                    val savedId = repository.saveOneOff(entity)
                    // The alarm is saved; its sound is secondary and must not make the save look failed (a retry
                    // would then create a second alarm).
                    val soundSaved = runCatching {
                        val live = repository.snapshot().oneOffs.map { it.id }.toSet() + savedId
                        container.settings.setOneOffSound(savedId, soundCode?.let(SoundRef::decode), live)
                    }.isSuccess
                    savedId to soundSaved
                },
                onSuccess = { (savedId, soundSaved) ->
                    // Set before anything else: if the screen stays open, saving again updates this alarm.
                    session.editId = savedId
                    if (!soundSaved) session.message(UiMessage(res.getString(R.string.oneoff_sound_save_failed)))
                    session.saved = true
                },
```

In the delete `ConfirmDialog`'s `action`, change it to:

```kotlin
                        action = {
                            repository.deleteOneOff(target)
                            runCatching { container.settings.setOneOffSound(target, null, repository.snapshot().oneOffs.map { it.id }.toSet()) }
                        },
```

Add the row after the label `IconField`:

```kotlin
                ringSettings?.let { rs ->
                    SoundRow(rememberSoundName(soundCode?.let(SoundRef::decode), rs.importedSounds)) { choosingSound = true }
                }
```

Show the sheet next to the `confirmDelete` dialog:

```kotlin
        val noPicker = stringResource(R.string.settings_no_sound_picker)
        val pickTone = rememberTonePicker(onPicked = { soundCode = it.encode() }, onUnavailable = { session.message(UiMessage(noPicker)) })
        val rs = ringSettings
        if (choosingSound && rs != null) {
            SoundChoiceSheet(
                title = stringResource(R.string.oneoff_sound),
                current = soundCode?.let(SoundRef::decode),
                imported = rs.importedSounds,
                onDismiss = { choosingSound = false },
                onPick = { ref ->
                    choosingSound = false
                    soundCode = ref?.encode()
                },
                onPickTone = {
                    choosingSound = false
                    pickTone((soundCode?.let(SoundRef::decode) as? SoundRef.SystemTone)?.uri)
                },
            )
        }
```

Add the row composable at the bottom of the file. It mirrors `IconField`'s 52 dp / 16 dp radius look:

```kotlin
/** "Sound": the alarm's sound override, or Default. Opens the sound choice sheet. */
@Composable
private fun SoundRow(name: String, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceHigh,
        contentColor = colors.onBg,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReveilleIcon(R.drawable.ic_music, null, tint = colors.muted, size = 20.dp)
            Text(stringResource(R.string.oneoff_sound), Modifier.weight(1f), style = ReveilleTheme.type.bodyL)
            Text(name, style = ReveilleTheme.type.body, color = colors.muted, maxLines = 1)
            ReveilleIcon(R.drawable.ic_chevron_right, null, tint = colors.muted, size = 16.dp)
        }
    }
}
```

Add these imports: `com.replydev.reveille.alarm.SoundRef`, `com.replydev.reveille.ui.components.ReveilleIcon`, `com.replydev.reveille.ui.settings.SoundChoiceSheet`, `com.replydev.reveille.ui.settings.rememberSoundName`, `com.replydev.reveille.ui.settings.rememberTonePicker`.

Check against `IconField` in `ui/components/Controls.kt` that the colours match. If `IconField` uses a different container token, use that token instead.

- [ ] **Step 2: Build, test and lint**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: all PASS, 0 lint errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/replydev/reveille/ui/oneoff/OneOffEditorScreen.kt
git commit -m "feat(ui): choose a sound for a one-off alarm"
```

---

### Task 10: Maestro flow

**Files:**
- Create: `.maestro/sounds/test_tone.mp3`, `.maestro/sounds/alarm_sound.yaml`, `scripts/maestro-sounds.sh`
- Modify: `AGENTS.md` (UI testing section)

- [ ] **Step 1: Generate the test file** (2 s, 440 Hz, under 20 KB):

```bash
ffmpeg -f lavfi -i "sine=frequency=440:duration=2" -ac 1 -b:a 48k .maestro/sounds/test_tone.mp3
```

- [ ] **Step 2: Write the script**

```bash
#!/usr/bin/env bash
# Runs the alarm sound flow: pushes a test MP3 into Download (the document picker reads it from there), then the flow.
set -euo pipefail
cd "$(dirname "$0")/.."
adb push .maestro/sounds/test_tone.mp3 /sdcard/Download/test_tone.mp3 >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/test_tone.mp3 >/dev/null || true
MAESTRO_CLI_NO_ANALYTICS=1 maestro test .maestro/sounds/alarm_sound.yaml
```

`chmod +x scripts/maestro-sounds.sh`

- [ ] **Step 3: Write the flow**

```yaml
# Import an MP3, make it the default, override one policy with it, check the names, then delete it.
appId: com.replydev.reveille
name: Alarm sound
---
- runFlow: ../subflows/launch_clean.yaml
- tapOn: "Policies"
- tapOn: "New policy"
- tapOn: "Name"
- inputText: "Gym"
- hideKeyboard
- runFlow:
    when:
      notVisible: "Remove alarm time"
    commands:
      - tapOn: "\\+ Alarm time"
- tapOn: "Save"
- tapOn: "Settings"
- assertVisible: "System default alarm"
- tapOn: "Alarm sound"
- assertVisible: "Default sound"
# Import through the system document picker.
- tapOn: "Import audio file…"
- runFlow:
    when:
      visible: "Downloads"
    commands:
      - tapOn: "Downloads"
- tapOn: "test_tone.mp3"
- assertVisible: "Imported test_tone"
# Make it the default.
- tapOn: "test_tone"
- assertVisible:
    checked: true
    containsDescendants:
      - text: "test_tone"
# Override Gym.
- scrollUntilVisible:
    element: "Gym"
- tapOn: "Gym"
- tapOn:
    text: "test_tone"
    below: "System default alarm"
- assertVisible:
    text: "test_tone"
    rightOf: "Gym"
# Back on Settings the row shows it.
- tapOn: "Back"
- assertVisible: "test_tone"
# Delete it: both choices go back.
- tapOn: "Alarm sound"
- tapOn: "Delete test_tone"
- assertVisible: "Delete sound?"
- tapOn:
    text: "Delete"
    below: "Delete sound?"
- assertNotVisible: "test_tone"
- assertVisible:
    text: "Default"
    rightOf: "Gym"
```

Check `subflows/launch_clean.yaml`'s relative path: Maestro resolves `runFlow` relative to the flow file, so `../subflows/...` is correct.

- [ ] **Step 4: Run it on the emulator**

```bash
$ANDROID_HOME/emulator/emulator -avd reveille -no-window -no-audio -no-boot-anim &
adb wait-for-device && ./gradlew :app:installDebug && scripts/maestro-sounds.sh
MAESTRO_CLI_NO_ANALYTICS=1 maestro test .maestro
```

Expected: all flows PASS. If the document picker's labels differ, use `maestro hierarchy` to find them and adjust only the picker steps.

- [ ] **Step 5: Document it.** In `AGENTS.md` › UI testing, after the i18n bullet, add:

```markdown
- `scripts/maestro-sounds.sh` pushes `.maestro/sounds/test_tone.mp3` into Download and runs `.maestro/sounds/alarm_sound.yaml` (import, default, per-policy override, delete). It lives in a subfolder because it needs that file on the device first.
```

- [ ] **Step 6: Commit**

```bash
git add .maestro/sounds scripts/maestro-sounds.sh AGENTS.md
git commit -m "test(ui): Maestro flow for importing and assigning an alarm sound"
```

---

### Task 11: Docs and mockups

**Files:**
- Modify: `docs/superpowers/specs/2026-11-02-reveille-design.md` (line 15 non-goals, line 54 settings, line 107 ringing)
- Modify: `docs/superpowers/specs/2026-11-11-custom-alarm-sounds-design.md` (§4: one "Default sound" card that lists the imported files with play/delete, and no separate "Imported sounds" card)
- Modify: `docs/design/README.md` (Settings section ~line 205; One-off sheet ~line 185; mockup table ~line 11)
- Create: `docs/design/mockups/AlarmSound.dc.html` (start from `RingPicker.dc.html`'s structure and `renderVals()` pattern)
- Modify: `docs/design/mockups/Settings.dc.html`, `docs/design/mockups/OneOff.dc.html`
- Modify: `AGENTS.md` (Layout: `data/SoundLibrary`, `alarm/SoundRef`)
- Modify: `docs/reports/2026-11-02-reveille-build-report.html` (device-only checks)

- [ ] **Step 1: Main spec.**
  - Line 15: remove "per-policy sounds" from the non-goals.
  - Line 54: replace "Ringtone URI" with "Alarm sound (`SoundRef`: system default, a system tone, or an imported file), with per-policy and per-one-off overrides".
  - Line 107: replace the sound order with: "the alarm's resolved sound (`resolveSound`: one-off override → policy override → default; snoozes carry their sources) → `RingtoneManager.getDefaultUri(TYPE_ALARM)` → bundled sound. Imported files live in device-protected `files/sounds/`, so they ring before the first unlock".
- [ ] **Step 2: Design README.**
  - Replace "It opens the system picker." with "It pushes the Alarm sound screen (`AlarmSound.dc.html`): a Default sound card (System default alarm, System tone, imported files with play/delete, Import audio file…) and a Per policy card whose rows open a choice sheet."
  - In the One-off sheet section, add "a Sound row (52 dp, `SurfaceHigh`, music icon) below the label field".
  - Add `AlarmSound.dc.html` to the mockup table.
- [ ] **Step 3: Mockups.** Create `AlarmSound.dc.html` with sample data: default "Morning birds", imported "Morning birds" and "Bells", policies "Work" (Default) and "Gym" (Bells). Add the Sound row to `OneOff.dc.html`. Make the caption in `Settings.dc.html` read "Morning birds". Open each in a browser to check that it renders.
- [ ] **Step 4: AGENTS.md layout.**
  - Under `data/`, add "`SoundLibrary`: imported alarm sounds, copied into device-protected `files/sounds/`."
  - Under `alarm/`, add "`SoundRef`: the sound choice, `resolveSound` and the fallback order."
- [ ] **Step 5: Build report.** Add these device-only checks:
  - An imported MP3 rings after a reboot, before the first unlock.
  - A custom sound plays at alarm volume while media volume is muted.
  - Deleting the ringing sound doesn't stop the current ring.
- [ ] **Step 6: Commit**

```bash
git add docs AGENTS.md
git commit -m "docs: custom alarm sounds in the spec, design and mockups"
```

---

### Task 12: Final verification

- [ ] **Step 1:** `./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Everything must pass, with 0 lint errors.
- [ ] **Step 2:** `./gradlew :app:installDebug && MAESTRO_CLI_NO_ANALYTICS=1 maestro test .maestro && scripts/maestro-sounds.sh && scripts/maestro-i18n.sh`. All must pass.
- [ ] **Step 3:** On the emulator, set an imported sound as the default, ring the test alarm (Health › Start), and check that the imported tone plays: `adb shell dumpsys media.player | grep -i sounds`, or listen with audio enabled.
- [ ] **Step 4:** Report what passed and what still needs a real phone (see the Task 11 build report items).
