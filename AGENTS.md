# AGENTS.md

Guidance for coding agents working on Reveille. `CLAUDE.md` is a symlink to this file.

## The one rule

**A wake-up must never be lost silently.** Every change on the alarm path must leave each scheduled occurrence in exactly one of two states: it rang, or it was reported missed. When in doubt, ringing twice beats not ringing. Read `docs/superpowers/specs/2026-11-02-reveille-design.md` (Firing path, Reliability) before touching `alarm/`, `data/` or `engine/ArmPlanner.kt`.

## Commands

```bash
./gradlew :engine:test                       # pure-Kotlin engine tests (fast)
./gradlew :app:testDebugUnitTest             # Robolectric tests
./gradlew :app:lintDebug                     # must report 0 errors
./gradlew :app:assembleDebug                 # build the APK
./gradlew :app:installDebug                  # install on a connected phone
maestro test .maestro                        # UI flows on the running emulator or phone
```

Run all four (test, test, lint, assemble) before committing. For UI changes, also install and run the Maestro flows.

## UI testing (Maestro)

`.maestro/` holds end-to-end flows driven by [Maestro](https://maestro.dev): `navigation.yaml`, `one_off_alarm.yaml`, `policy.yaml`, `update.yaml`. Each one starts from `subflows/launch_clean.yaml` (except `update.yaml`, which launches with the `fake_updates` argument so the debug build serves a canned release) (cleared state, all permissions granted). Only top-level files run as flows.

- Setup: `brew install mobile-dev-inc/tap/maestro`; emulator `reveille` (Pixel 8, API 35 `google_apis` arm64). Start it headless with `$ANDROID_HOME/emulator/emulator -avd reveille -no-window -no-audio -no-boot-anim &`.
- Set `MAESTRO_CLI_NO_ANALYTICS=1` to keep Maestro from sending analytics.
- Select elements by their visible text or, for icon-only buttons, their `contentDescription`. Top-level screens are reached through the bottom bar ("Today", "Policies", "Health", "Settings"). When a label appears twice, anchor it with `below:`.
- `maestro hierarchy` dumps what is on screen; `adb exec-out screencap -p > shot.png` takes a screenshot.
- To check motion, record with `adb shell screenrecord`, then tile the distinct frames: `ffmpeg -i nav.mp4 -vf "mpdecimate,setpts=N/FRAME_RATE/TB,scale=270:-1,tile=8x2" -frames:v 1 grid.png`.
- Flows assume the device is in `en-US` and select English labels. `scripts/maestro-i18n.sh` runs `.maestro/i18n/` with the app set to Italian. It runs `pm clear` itself, because clearing app data also drops the per-app locale, so that flow launches without `clearState`. Maestro doesn't recurse into subfolders, so `maestro test .maestro` skips it.
- An emulator cannot prove HyperOS kills, Direct Boot ringing or full-screen intents; those still need a real phone.

## Layout

- `engine/`: pure Kotlin, `java.time` only, no Android. `Model.kt` (policies, rules, one-offs, skips, `Source` keys, `Armed`), `ScheduleEngine.kt` (`upcoming`, `between`, DST resolution, global ordering), `ArmPlanner.kt` (what to arm, catch-up within 30 min, missed beyond), `Timeline.kt`, `TimeText.kt` (returns values, not text: `DayLabel`, `Relative`, `DaySet`). The engine holds no words; the default one-off label comes in through `ScheduleSnapshot.defaultOneOffLabel`.
- `app/src/main/java/com/replydev/reveille/`:
  - `data/`: Room DB and DataStore, both in **device-protected storage**. `ScheduleRepository` calls `onChanged()` (which re-arms) after every write, under `NonCancellable`.
  - `alarm/`:
    - `AlarmScheduler`: `rearm()` is mutex-protected. It registers a primary and a backup alarm (+2 min), the ring guard and the direct snooze, and runs `restoreGuard`.
    - `AlarmReceiver` and `RingingService`: delivery and ringing.
    - `AlarmFiring` and `RingDecisions`: the delivery protocol, kept testable.
    - `SystemEventsReceiver`: boot, time, time zone and locale changes.
    - `WatchdogWorker`: 6 h safety re-arm.
  - `reliability/`: permission, battery and HyperOS checks.
  - `widget/`: Glance widget. It renders the persisted armed alarm and never computes its own.
  - `update/`: in-app updates from GitHub Releases (`ReleaseSource`, `UpdateChecker`, `UpdateWorker`, `UpdateDownloader`, `ApkVerifier`, `UpdateInstaller`, `InstallGuard`). Spec: `docs/design/updates.md`. The only package that touches the network.
  - `text/`: `TimeFormats` (locale and 12/24-hour aware times, dates, weekdays, sizes; `TimeFormats.of(context)`), `Labels` (engine time values → words via resources and plurals). The only place that turns values into text. In Compose use `ui/text/rememberLabels()`.
  - `ui/`: Compose screens. Writes go through `ScreenSession`, which launches in `AppContainer.appScope`, never the screen's scope.
  - `ui/theme/`: `Color.kt` (fixed dark palette + `ReveilleColors` CompositionLocal), `Type.kt` (Geist / Geist Mono, tabular numerals), `Theme.kt`, `Motion.kt`. No dynamic colour.
- `AppContainer.kt`: manual DI. No DI framework.

## Design

The UI follows `docs/design/README.md` (tokens, components, per-screen spec) and the mockups in `docs/design/mockups/*.dc.html` (1 CSS px = 1 dp/sp; sample data is in each file's `renderVals()`). Canvas: https://claude.ai/artifact/9ZYXbAyXxZyZL3u5RVp2pu.

- Use the tokens in `ui/theme/` and the shared components in `ui/components/`. Never hard-code a hex colour or font size in a screen.
- Dark-only, fixed palette; `Accent` (#FFB547) is the single accent. Policy colours come from `policyColor(id)`, not the database.
- Navigation: a bottom bar with Today / Policies / Health / Settings. The editors are pushed screens (policy) or a bottom sheet (one-off) without the bar.
- Cards are flat (1 dp outline, no elevation). Touch targets are ≥ 44 dp. Text meets 4.5:1 contrast; `Faint` is never used for body text.
- In-app updates: `docs/design/updates.md` and `mockups/Update*.dc.html`.
- When a screen changes, update its mockup and the spec if the design itself changed, not just the code.

## Invariants (don't break these)

- **Occurrence keys**: `P:{policyId}:{ruleId}:{date}`, `O:{oneOffId}`, `S:{epochMillis}`. `ScheduleDao.savePolicy` keeps rule ids stable; don't go back to delete-and-reinsert.
- **Fire log** is unique on `(occurrenceKey, scheduledAt)`. An insert with IGNORE is the "claim"; outcomes are FIRED → DISMISSED / SNOOZED / AUTO_SNOOZED / SKIPPED / SUPERSEDED / MISSED. FIRED means claimed but unresolved, and the ring guard protects it.
- **Delivery order**: the receiver calls `startForegroundService` synchronously, before any DB work. The service calls `startForeground` first, then claims and re-arms. Any start failure must post the fallback notification and arm the ring guard.
- **Snooze/Dismiss** persist under `NonCancellable` before the sound stops. They must never leave the alarm unstoppable.
- **Direct Boot**: nothing on the alarm path may touch credential-encrypted storage. WorkManager (on-demand init; the default initializer is removed in the manifest) and Glance are only used when `UserManager.isUserUnlocked`.
- **API level**: minSdk 31. Don't call APIs above 31 without an SDK check. `LocalDate.ofInstant` is API 34; use `instant.atZone(zone).toLocalDate()`. Lint `NewApi` must stay clean, and `:engine` isn't linted, so be careful there too.
- **Engine purity**: `:engine` stays free of Android dependencies.
- **Text never breaks the alarm path**: time formatting must never throw (`TimeFormats` falls back per pattern), and notifier calls during a re-arm are guarded, so a failing notification can't abort it.
- **Updates never endanger an alarm**: nothing in `alarm/`, `engine/` or `data/` depends on `update/`; installs are refused while ringing and guarded within 30 min of the next alarm; `MY_PACKAGE_REPLACED` must re-arm (tested). An APK is installed only if its SHA-256 matches the release's `.sha256` asset and its signing certificate, package name and higher versionCode match the installed app.

## Testing

- TDD: write the failing test first.
- Alarm-path Robolectric tests run at `@Config(sdk = [31, 33, 35])`. `app/src/test/resources/robolectric.properties` pins the default to 35.
- `app/src/test/java/com/replydev/reveille/testing/Flaky.kt` provides failure-injection doubles for storage-failure paths.
- `app/build.gradle.kts` sets `maven.repo.local` for unit tests, because a `${user.home}` in `~/.m2/settings.xml` isn't expanded by Robolectric. Never commit anything under `app/${user.home}/` (it's git-ignored).
- Things unit tests can't prove (HyperOS kills, Direct Boot ringing, full-screen intents) are listed in `docs/reports/2026-11-02-reveille-build-report.html`. Verify them on a device.

## Conventions

- Kotlin official style. Match the surrounding code's comment density and naming.
- UI strings live in `res/values/strings.xml` (English, the source) with translations in `values-it`, `values-es`, `values-fr`, `values-de`. Never hard-code user-visible text; add every key to every locale (`app/lint.xml` makes `MissingTranslation` and `ExtraTranslation` errors). Use plurals for counts (with a `many` form for it/es/fr) and positional args (`%1$s`). Format times, dates and sizes only through `text/TimeFormats` and `text/Labels`; never `Locale.ENGLISH` for user-visible text. Language follows the system or per-app setting; there is no in-app picker. See `docs/design/i18n.md`.
- No Material icons artifact: icons are hand-written vector drawables in `res/drawable/ic_*.xml`. Icon-only buttons always have a `contentDescription`, which is also what Maestro selects on.
- Commits: conventional style (`feat(alarm): …`, `fix(ui): …`, `docs: …`), unsigned. **No AI attribution lines or Co-Authored-By trailers.**
- Commit types drive releases: merging to `main` publishes a release for `feat` (minor), `fix`/`perf` (patch) and `!`/`BREAKING CHANGE` (major); `docs`, `chore`, `ci`, `test`, `refactor` and `build` don't release. Pick the type deliberately.
- Never commit keystores (`*.jks`, `*.keystore` are git-ignored). Release signing only happens in CI from secrets.
- Keep the spec in sync when behaviour on the alarm path changes.
