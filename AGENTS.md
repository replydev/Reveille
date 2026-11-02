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
```

Run all four (test, test, lint, assemble) before committing.

## Layout

- `engine/`: pure Kotlin, `java.time` only, no Android. `Model.kt` (policies, rules, one-offs, skips, `Source` keys, `Armed`), `ScheduleEngine.kt` (`upcoming`, `between`, DST resolution, global ordering), `ArmPlanner.kt` (what to arm, catch-up within 30 min, missed beyond), `Timeline.kt`, `TimeText.kt`.
- `app/src/main/java/com/replydev/reveille/`:
  - `data/`: Room DB and DataStore, both in **device-protected storage**. `ScheduleRepository` calls `onChanged()` (which re-arms) after every write, under `NonCancellable`.
  - `alarm/`:
    - `AlarmScheduler`: `rearm()` is mutex-protected. It registers a primary and a backup alarm (+2 min), the ring guard and the direct snooze, and runs `restoreGuard`.
    - `AlarmReceiver` and `RingingService`: delivery and ringing.
    - `AlarmFiring` and `RingDecisions`: the delivery protocol, kept testable.
    - `SystemEventsReceiver`: boot, time and time zone changes.
    - `WatchdogWorker`: 6 h safety re-arm.
  - `reliability/`: permission, battery and HyperOS checks.
  - `widget/`: Glance widget. It renders the persisted armed alarm and never computes its own.
  - `ui/`: Compose screens. Writes go through `ScreenSession`, which launches in `AppContainer.appScope`, never the screen's scope.
- `AppContainer.kt`: manual DI. No DI framework.

## Invariants (don't break these)

- **Occurrence keys**: `P:{policyId}:{ruleId}:{date}`, `O:{oneOffId}`, `S:{epochMillis}`. `ScheduleDao.savePolicy` keeps rule ids stable; don't go back to delete-and-reinsert.
- **Fire log** is unique on `(occurrenceKey, scheduledAt)`. An insert with IGNORE is the "claim"; outcomes are FIRED → DISMISSED / SNOOZED / AUTO_SNOOZED / SKIPPED / SUPERSEDED / MISSED. FIRED means claimed but unresolved, and the ring guard protects it.
- **Delivery order**: the receiver calls `startForegroundService` synchronously, before any DB work. The service calls `startForeground` first, then claims and re-arms. Any start failure must post the fallback notification and arm the ring guard.
- **Snooze/Dismiss** persist under `NonCancellable` before the sound stops. They must never leave the alarm unstoppable.
- **Direct Boot**: nothing on the alarm path may touch credential-encrypted storage. WorkManager (on-demand init; the default initializer is removed in the manifest) and Glance are only used when `UserManager.isUserUnlocked`.
- **API level**: minSdk 31. Don't call APIs above 31 without an SDK check. `LocalDate.ofInstant` is API 34; use `instant.atZone(zone).toLocalDate()`. Lint `NewApi` must stay clean, and `:engine` isn't linted, so be careful there too.
- **Engine purity**: `:engine` stays free of Android dependencies.

## Testing

- TDD: write the failing test first.
- Alarm-path Robolectric tests run at `@Config(sdk = [31, 33, 35])`. `app/src/test/resources/robolectric.properties` pins the default to 35.
- `app/src/test/java/com/replydev/reveille/testing/Flaky.kt` provides failure-injection doubles for storage-failure paths.
- `app/build.gradle.kts` sets `maven.repo.local` for unit tests, because a `${user.home}` in `~/.m2/settings.xml` isn't expanded by Robolectric. Never commit anything under `app/${user.home}/` (it's git-ignored).
- Things unit tests can't prove (HyperOS kills, Direct Boot ringing, full-screen intents) are listed in `docs/reports/2026-11-02-reveille-build-report.html`. Verify them on a device.

## Conventions

- Kotlin official style. Match the surrounding code's comment density and naming. UI strings are English and hard-coded.
- No Material icons artifact; buttons use text labels.
- Commits: conventional style (`feat(alarm): …`, `fix(ui): …`, `docs: …`), unsigned. **No AI attribution lines or Co-Authored-By trailers.**
- Keep the spec in sync when behaviour on the alarm path changes.
