# Custom alarm sounds — design

Date: 2026-11-11. Status: approved.

## Goal

Let the user pick any audio file (MP3 and other formats `MediaPlayer` reads) as an alarm sound, as the global default
and as an override per policy and per one-off alarm. A custom sound must never cost a wake-up: if it can't be played,
the existing fallback chain rings instead.

This lifts the v1 non-goal "per-policy sounds" in `2026-11-02-reveille-design.md`.

## Decisions

- **Imported files are copied** into device-protected storage. A `content://` URI from the document picker can't be
  read before the first unlock and breaks when the file moves; a private copy rings exactly like the bundled sound.
- **Overrides live in DataStore**, next to the ringing-theme overrides. No Room migration, nothing in `:engine`.
- **Per-policy overrides are set on the Alarm sound screen** (mirroring the Ringing theme screen); the policy editor is
  unchanged. Per-one-off overrides are set in the one-off sheet.

## 1. Data

`SoundRef` (package `alarm/`, next to `RingTheme`):

```kotlin
sealed interface SoundRef {
    data object SystemDefault : SoundRef          // RingtoneManager.getDefaultUri(TYPE_ALARM)
    data class SystemTone(val uri: String) : SoundRef
    data class Imported(val id: String) : SoundRef
}
```

Stored as a string: `default`, `tone:<uri>`, `file:<id>`. Anything unreadable decodes to `SystemDefault`. Decoding
never throws.

`RingSettings` changes:

| Field | Type | Notes |
|---|---|---|
| `sound` | `SoundRef` | Replaces `ringtoneUri`. Key `sound`. If absent, read from the legacy `ringtone_uri` key (`tone:<uri>`, or `SystemDefault` if that is absent too). The first write stores `sound` and removes `ringtone_uri`. |
| `policySounds` | `Map<Long, SoundRef>` | String set `"<policyId>=<encoded ref>"`; malformed entries skipped. |
| `oneOffSounds` | `Map<Long, SoundRef>` | Same encoding, keyed by one-off id. |
| `importedSounds` | `List<ImportedSound>` | `ImportedSound(id: String, displayName: String)`; string set `"<id>=<name>"` (split on the first `=`), sorted by name for display. |

All of it lives in the existing device-protected `reveille` DataStore.

Imported files live at `<deviceProtectedFilesDir>/sounds/<id>`, where `id` is a random UUID. The original extension
is not needed: `MediaPlayer` detects the format from the content.

## 2. Resolution and playback (alarm path)

`resolveSound(armed: Armed, settings: RingSettings): SoundRef` is pure and lives with `resolveTheme`:

1. One-off override: the first `O:<id>` among `armed.key` and `armed.sourceKeys` that has an entry in `oneOffSounds`.
2. Policy override: `ringPolicySource(armed)?.policyId` looked up in `policySounds`.
3. `settings.sound`.

Snoozes carry the sources of the alarm they snooze, so a snoozed alarm keeps its sound. In a merged occurrence
(policy + one-off at the same instant), the one-off's override wins over the policy's because it was set for that
specific alarm. The test alarm and unknown keys use `settings.sound`.

`RingingService.prepare` tries, in order:

1. the resolved sound: `Imported` → `setDataSource(file.path)` (a missing file throws); `SystemTone` →
   `setDataSource(context, uri)`; `SystemDefault` → skip straight to step 2;
2. `RingtoneManager.getDefaultUri(TYPE_ALARM)`;
3. the bundled `R.raw.fallback_alarm`.

Each failure is logged and the player is `reset()` before the next step. If step 3 also fails, the existing path still
applies: post the fallback notification and vibrate whatever the setting says. Settings that can't be read within the
existing 3 s timeout mean defaults, so `SystemDefault`, as today.

Nothing in `alarm/` imports the UI or `SoundLibrary`'s import code. The ringing path only reads settings and a file path.

## 3. SoundLibrary

`SoundLibrary(context, settingsStore, deviceStorage)`, created in `AppContainer`.

- `file(id): File`: the path for an imported id. Used by `RingingService`.
- `suspend fun import(uri: Uri): ImportResult`, on `Dispatchers.IO`:
  1. read `OpenableColumns.DISPLAY_NAME` and `SIZE`. If the size is over 20 MB → `TooLarge`;
  2. copy the stream to `sounds/<id>.tmp`, aborting once 20 MB is exceeded (size can be unknown) → `TooLarge`;
  3. `MediaPlayer().setDataSource(tmp); prepare()`. If that fails → `NotAudio`;
  4. rename to `sounds/<id>`, then add `ImportedSound(id, displayName)` to `importedSounds` in one settings write;
  5. return `Imported(sound)`. Any I/O error → `Unreadable`. Every failure deletes the temp file.
- `suspend fun delete(id)`: one settings write removes the entry, resets the global `sound` to `SystemDefault` if it
  was `SoundRef.Imported(id)`, and removes every policy and one-off override that used it, so they follow the global
  "Default" again. The file is deleted only after that write. An alarm that reads
  settings in between plays the default; one that read them just before finds no file and falls through the chain.
- `suspend fun sweepOrphans()`: deletes files in `sounds/` (including `*.tmp`) that have no entry. It runs once per
  process start from `ReveilleApp`, in `appScope`, after the user is unlocked. It never runs on the alarm path.

Override pruning, mirroring `setPolicyRingTheme`:

- `SettingsStore.setPolicySound(policyId, ref?, livePolicyIds)` and `setOneOffSound(oneOffId, ref?, liveOneOffIds)`.
  `null` clears the override. Each write drops overrides whose ids are no longer live.
- When a one-off is deleted from the UI, its override is cleared in the same `ScreenSession` write. Overrides of
  one-offs that have fired and been removed are dropped at the next override write. They are a few bytes, and
  `resolveSound` ignores them because no alarm carries that key any more. One accepted edge case: a one-off is
  removed when it fires, so if another override write prunes its entry while it is snoozed, the snooze rings with the
  policy or global sound. It still rings; only the tone differs.

## 4. UI

Follows `docs/design/README.md`: tokens only, flat cards, targets ≥ 44 dp, every string in en/it/es/fr/de.

**Settings → Sound card.** The "Alarm sound" row keeps its look (icon tile, current sound name in `Muted`, chevron) but
now pushes the **Alarm sound** screen instead of opening the system picker directly.

**Alarm sound screen** (`ui/settings/AlarmSoundScreen.kt`, pushed, no bottom bar, back arrow):

- *Default sound* group: a radio list with "System default alarm", "System tone" (caption: the chosen tone's name;
  tapping it opens the existing `ACTION_RINGTONE_PICKER` and selects the result), and each imported sound.
- *Imported sounds* group: one row per file with a ▶/■ preview button (`contentDescription` "Play <name>" / "Stop")
  and a delete button ("Delete <name>"). Deleting a sound that is in use asks for confirmation first ("Used by N
  alarms; they'll go back to the default sound"). Below the rows sits "Import audio file…", which launches
  `ActivityResultContracts.OpenDocument` with `audio/*`. While an import runs, the row shows progress and is disabled.
  Results arrive as snackbar messages: imported / too large (20 MB max) / not a playable audio file / couldn't read
  the file.
- *Per policy* group: one row per policy (colour dot, name, current sound or "Default"). Tapping a row opens a bottom
  sheet: "Default", "System default alarm", each imported sound. "System tone…" opens the system picker.
- Preview uses a `MediaPlayer` with `USAGE_ALARM`. Only one plays at a time, and it stops on leaving the screen or
  starting another preview.

**One-off sheet** (`OneOffEditorScreen`): a "Sound" row ("Default" or the sound's name) opens the same choice sheet.
The choice is saved with the one-off. A new one-off's override is written after the insert returns its id, inside the
same `ScreenSession` write.

**Display names**: an imported sound shows its file name without the extension. `SystemTone` uses
`RingtoneManager.getRingtone(...).getTitle`, off the main thread, falling back to "Custom sound". A reference to a
deleted file shows "Default".

**Icons** (hand-written vectors): `ic_play`, `ic_stop`, `ic_upload`, `ic_delete` (reuse any that already exist).

**Mockups**: new `docs/design/mockups/AlarmSound.dc.html`; update `Settings.dc.html` and `OneOff.dc.html`.

## 5. Error handling summary

| Failure | Result |
|---|---|
| Imported file missing or corrupt at ring time | Logged; the system default alarm plays (then the bundled sound) |
| Ringing before the first unlock | Imported file is readable (device-protected); `SystemTone` fails → bundled sound, as today |
| Settings unreadable at ring time | Defaults → system default alarm, as today |
| Import too large, unreadable or not audio | Snackbar; temp file deleted; nothing stored |
| Crash during import or delete | Orphan sweep at next start removes stray files; refs to a missing file fall through the chain |
| Delete while the sound is ringing | The player already has the file open; ringing continues. The next alarm uses the default |

## 6. Testing

TDD; alarm-path Robolectric tests at `@Config(sdk = [31, 33, 35])`.

- Unit: `SoundRef` encode/decode, including garbage input; `resolveSound` precedence (global, policy, one-off, merged
  occurrence, snooze of each, test alarm, unknown key); `SettingsStore` legacy `ringtone_uri` migration; override
  pruning; `importedSounds` encoding with `=` in names.
- Robolectric: `RingingService` with an `Imported` ref whose file is missing → still rings (fallback path), no
  fallback notification; `SoundLibrary.import` rejects files that are too large or not audio and leaves no temp file;
  `delete` resets every ref, then removes the file; `sweepOrphans` removes stray files and keeps listed ones.
- Maestro: `.maestro/alarm_sound.yaml`. Push `test.mp3` into Downloads with `adb push`, import it through the
  document picker, set it as the default, assign it to a policy, and check the names shown.
- Device only (added to the build report): an imported MP3 rings after reboot before the first unlock; a custom sound
  plays at alarm volume while media is muted.

## 7. Docs to update

- `2026-11-02-reveille-design.md`: Settings (sound refs, overrides), Firing path (resolution and the fallback chain),
  Non-goals (drop "per-policy sounds").
- `docs/design/README.md`: Settings section (Alarm sound screen) and the one-off sheet.
- `AGENTS.md` layout: `SoundLibrary` and the `sounds/` directory in device-protected storage.
