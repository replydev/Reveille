# Alarm Scheduler — Design

Date: 2026-11-02
Status: Draft, awaiting review

## Goal

A personal Android alarm app with more scheduling flexibility than the stock Clock app:

- One-off alarms on a specific date ("09:30 next Thursday only"), without creating a recurrence.
- **Policies**: named groups of recurring rules (e.g. "Work" = 07:55 Mon/Tue/Wed + 08:55 Thu/Fri).
- A home-screen widget showing the next wake-up.
- **Never miss a wake-up**, including on OEM ROMs with aggressive battery management (Xiaomi HyperOS/MIUI, Samsung, etc.).

Non-goals (v1): Play Store distribution, cloud sync/backup, per-policy sounds, holiday calendars, smart/sleep-tracking alarms.

## Platform & stack

- Kotlin, Jetpack Compose (Material 3), Room, Glance (App Widget).
- `minSdk 31` (Android 12), `targetSdk` = latest stable.
- Sideloaded via `adb`. Uses `USE_EXACT_ALARM` (auto-granted, no user prompt; acceptable outside Play Store).
- No DI framework: a small manual `AppContainer` in `Application`.

## Data model (Room)

**Policy**
- `id`, `name`, `enabled: Boolean`
- `startDate: LocalDate?`, `endDate: LocalDate?` (inclusive; null = unbounded)

**Rule** (belongs to one Policy)
- `id`, `policyId`, `time: LocalTime`, `daysOfWeek: Set<DayOfWeek>`
- `intervalWeeks: Int` (default 1), `anchorDate: LocalDate` (a date in a week that "counts"; week parity computed from ISO week start, Monday)
- `label: String?` (overrides policy name in UI/notifications)

**Pause** (belongs to one Policy)
- `id`, `policyId`, `from: LocalDate`, `to: LocalDate` (inclusive)

**OneOff**
- `id`, `date: LocalDate`, `time: LocalTime`, `label: String?`
- `mode: REPLACE_DAY | ADDITIVE` (default `REPLACE_DAY`)
- `REPLACE_DAY` suppresses **all** policy occurrences on that date. One-offs never suppress other one-offs.

**Skip**
- `id`, `policyId`, `date: LocalDate` — suppresses that policy's occurrences on that date.
- Skipping a one-off = deleting it (with undo snackbar).

**FireLog**
- `occurrenceKey`, `scheduledAt: Instant`, `firedAt: Instant?`, `outcome: DISMISSED | SNOOZED | MISSED_DETECTED`
- Used for snooze state, missed-alarm detection, and debugging reliability.

**Settings** (DataStore, not Room)
- Ringtone URI, vibration on/off, snooze minutes (default 9), max ring duration before auto-snooze (default 10 min), volume ramp on/off.

All times are stored as local wall-clock values (`LocalDate`/`LocalTime`), never as epoch instants; conversion to `Instant` happens only in the engine using the current zone.

## Scheduling engine (pure Kotlin module)

A separate Gradle module `:engine` with no Android dependencies.

```kotlin
data class Occurrence(val key: String, val at: ZonedDateTime, val label: String, val source: Source)

fun upcoming(
    snapshot: ScheduleSnapshot,      // policies, rules, pauses, oneOffs, skips
    from: Instant,                   // exclusive
    zone: ZoneId,
    limit: Int,
    horizonDays: Int = 400,
): List<Occurrence>
```

Algorithm, per calendar day `d` from `from`'s date forward:
1. Collect policy occurrences: for each enabled policy whose `[startDate, endDate]` contains `d`, not paused on `d`, not skipped on `d`, for each rule where `d.dayOfWeek in daysOfWeek` and `weeksBetween(anchorWeek, week(d)) % intervalWeeks == 0`.
2. If any `REPLACE_DAY` one-off exists on `d`, drop all policy occurrences on `d`.
3. Add one-offs on `d`.
4. Resolve to `ZonedDateTime` with `ZonedDateTime.of(d, time, zone)`; DST gap → shifted forward (java.time default); DST overlap → earlier offset.
5. Keep those strictly after `from`; deduplicate identical instants (merge labels); sort.

`occurrenceKey` is stable: `"P:{ruleId}:{date}"` or `"O:{oneOffId}"`, so skips/logs/snooze can refer to occurrences.

## Android layer

### AlarmScheduler
Single entry point `rearm(reason)`, called after any data change, after firing, and from all system receivers. It:
1. Computes the next occurrence via the engine (also considering an active snooze, which is just an extra pending instant stored in DataStore).
2. Calls `AlarmManager.setAlarmClock(AlarmClockInfo(at, showIntent), firePendingIntent)` with a fixed request code (replacing any previous one).
3. Arms the **backup alarm** (see Reliability).
4. Writes the "next alarm" summary for the widget and requests a widget update.
5. Is idempotent and safe to call from any thread (serialized via a Mutex).

### Firing path
`AlarmReceiver` (exported=false) receives the fire intent →
1. Acquires a partial wake lock.
2. Starts `RingingService` (foreground, type `systemExempted`, permitted for apps holding `USE_EXACT_ALARM`; alarm-triggered FGS starts are exempt from background-start restrictions).
3. `RingingService` plays the ringtone via `MediaPlayer` with `AudioAttributes.USAGE_ALARM` (ignores ringer/silent mode), vibrates, and posts a high-priority notification on an `IMPORTANCE_HIGH` alarm channel with a **full-screen intent** to `RingingActivity` and Dismiss/Snooze actions.
4. Records `FireLog`, then calls `rearm()` so the next alarm is armed **before** the user interacts.

If the full-screen intent is denied, the service still rings and the heads-up notification still has Dismiss/Snooze — sound never depends on the activity launching.

`RingingActivity`: shows over lock screen (`setShowWhenLocked`, `setTurnScreenOn`), big time + label, Dismiss and Snooze.

Auto-snooze after max ring duration; after 3 auto-snoozes, stop and post a "Missed alarm" notification.

### System receivers → `rearm()`
`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.

## Reliability (critical requirement)

Losing a wake-up is the worst failure. Defense in depth:

1. **`setAlarmClock()` only.** It is the strongest AlarmManager API: exempt from Doze and App Standby, fires exactly, and is the same mechanism stock clock apps use. Never use `setExact`/`setAndAllowWhileIdle`/WorkManager for the actual alarm.

2. **Direct Boot support.** App components involved in firing are `directBootAware`; the schedule snapshot and settings are mirrored to **device-protected storage** so alarms re-arm and ring after a reboot *before* the phone is unlocked (e.g. overnight OS update). Room DB lives in device-protected storage (single-user personal app; data is not sensitive).

3. **Backup alarm.** Alongside the primary, arm a second `setAlarmClock` alarm (different request code) at `next + 2 min`. When the primary fires it cancels the backup. If the primary was swallowed, the backup rings with a "backup alarm" label.

4. **Watchdog / self-healing.** `rearm()` is also triggered by:
   - app open, widget update, and a periodic `WorkManager` job (every ~6 h) as a cheap safety net;
   - each run verifies `AlarmManager.getNextAlarmClock()` matches our expected trigger; if not, re-arm and log it.

5. **Missed-alarm detection.** On any `rearm()`, if FireLog shows a scheduled occurrence in the past with no `firedAt`, post a high-priority "Missed alarm at 07:55" notification and show it in the app — so failures are visible, never silent.

6. **OEM setup checklist (HyperOS/MIUI first-class).** A "Reliability" screen, also shown as a blocking banner on Home until all green:
   - Notifications permission (`POST_NOTIFICATIONS`) — granted?
   - Full-screen intent (`NotificationManager.canUseFullScreenIntent()`, Android 14+) — granted? Deep link to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
   - Exact alarms (`canScheduleExactAlarms()`) — should always be true with `USE_EXACT_ALARM`; checked anyway.
   - Battery optimization ignored (`isIgnoringBatteryOptimizations`) — request via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (allowed for sideloaded app).
   - Alarm channel not muted / importance high.
   - **Xiaomi/HyperOS specific** (detected via `Build.MANUFACTURER` / `ro.mi.os.version.name`), manual steps with deep links where available, falling back to app details screen:
     - Autostart ON (`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`) — without it `BOOT_COMPLETED` is not delivered.
     - Battery saver → "No restrictions" (`com.miui.powerkeeper/.ui.HiddenAppsConfigActivity`).
     - "Show on lock screen" and "Display pop-up windows while running in background" permissions (`com.miui.securitycenter` permission editor `miui.intent.action.APP_PERM_EDITOR`).
     - Advice: lock the app in Recents.
     These cannot be read programmatically, so each has a "I've done this" checkbox plus a **Test alarm** button.
   - Generic fallback links for Samsung/OnePlus/Oppo/Huawei pointing at app battery settings (based on dontkillmyapp.com guidance).

7. **Test alarm.** "Ring in 1 minute" button, also usable with screen off — the user validates the whole path end to end after setup, and the result is logged.

8. **Force-stop caveat.** A user/system force-stop clears all alarms and nothing can prevent it. Mitigation: the watchdog re-arms on next app/widget/boot event, and missed-alarm detection surfaces the gap. Documented in the Reliability screen.

## Widget (Glance)

- Shows next occurrence: `Thu 08:55 · Work` and relative time ("in 19 h"). Empty state: "No alarms".
- **Skip** button: skips that occurrence (policy → Skip row; one-off → delete) then `rearm()`.
- Tap anywhere else → opens app.
- Updated by `rearm()`; also a `DATE_CHANGED`/midnight refresh so relative text stays correct. Widget reads the precomputed summary, never computes on its own.
- If the reliability checklist is not green, widget shows a small warning icon.

## Screens (Compose)

1. **Home / Next up**: reliability banner (if needed), timeline of the next 14 days of occurrences grouped by day; swipe/tap to skip or un-skip; FAB → new one-off or policy.
2. **Policies**: list with enable toggles; editor with name, start/end dates, rules (time, weekday chips, every N weeks + anchor), pauses list.
3. **One-off editor**: date, time, label, Replace day / Ring in addition toggle; shows which policy alarms would be replaced.
4. **Settings**: ringtone, vibration, snooze length, max ring duration, volume ramp.
5. **Reliability**: checklist, test alarm, recent FireLog entries.

## Error handling

- Invalid input prevented in editors (rule with no weekdays, endDate < startDate, pause from > to).
- Engine horizon capped at 400 days; empty result → widget "No alarms", primary/backup cancelled.
- All DB writes go through a repository that calls `rearm()` afterwards in the same coroutine; failures surface as snackbars.
- Late firing (e.g. after reboot past the alarm time): if within 30 min of scheduled time, ring anyway; otherwise mark missed and notify.

## Testing

- **Engine unit tests (JVM, extensive)**: single rule; multiple rules in one policy; every-N-weeks with anchors; start/end windows; pauses; skips; REPLACE_DAY vs ADDITIVE; multiple one-offs same day; duplicate instants; DST gap/overlap (Europe/Rome); time zone change; horizon/empty.
- **Repository/DAO tests**: Room in-memory instrumented tests.
- **Scheduler tests**: Robolectric with shadow AlarmManager — verifies primary + backup armed, replaced on change, cancelled when empty.
- **Manual on device (HyperOS phone)**: test alarm with screen off; reboot before unlock; airplane mode; battery saver on; overnight real alarm.

## Project layout

```
:app      Android app (UI, Room, receivers, services, widget)
:engine   Pure Kotlin scheduling engine + tests
```
