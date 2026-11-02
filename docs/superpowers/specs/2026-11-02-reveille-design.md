# Reveille — Design

Date: 2026-11-02
Status: Implemented, pending device verification

## Goal

**Reveille** is a personal Android alarm app with more scheduling flexibility than the stock Clock app:

- One-off alarms on a specific date ("09:30 next Thursday only"), without creating a recurrence.
- **Policies**: named groups of recurring rules (e.g. "Work" = 07:55 Mon/Tue/Wed + 08:55 Thu/Fri).
- A home-screen widget showing the next wake-up.
- **Never miss a wake-up**, including on OEM ROMs with aggressive battery management (Xiaomi HyperOS/MIUI, Samsung, etc.).

Non-goals (v1): Play Store distribution, cloud sync/backup, per-policy sounds, holiday calendars, smart/sleep-tracking alarms.

## Platform & stack

- App name: **Reveille**; application ID `com.replydev.reveille`.
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
- `policyId`, `date: LocalDate` (composite primary key, no id) — suppresses that policy's occurrences on that date.
- Skipping a one-off = deleting it (with undo snackbar).

**FireLog**
- `id`, `occurrenceKey`, `label`, `scheduledAt` (epoch millis), `firedAt?`, `outcome: FIRED | DISMISSED | SNOOZED | AUTO_SNOOZED | SKIPPED | MISSED | SUPERSEDED` (unique on key + time). SKIPPED = a past catch-up skipped from the widget; SUPERSEDED = replaced by a newer ringing alarm.
- Pruned by `rearm()`: rows scheduled more than 60 days ago (never rows the current decision still needs).
- A row = handled for the planner. FIRED = claimed but not yet resolved (the ring guard retries it); the rest are terminal. Used for dedupe, missed-alarm detection, and debugging reliability.

**Settings** (DataStore, not Room)
- Ringtone URI, vibration on/off, snooze minutes (default 9), max ring duration before auto-snooze (default 10 min), volume ramp on/off.

All times are stored as local wall-clock values (`LocalDate`/`LocalTime`), never as epoch instants; conversion to `Instant` happens only in the engine using the current zone.

## Scheduling engine (pure Kotlin module)

A separate Gradle module `:engine` with no Android dependencies.

```kotlin
// sources: every alarm merged into this instant (policy rules and/or one-offs); key = sources.first().key
data class Occurrence(val at: ZonedDateTime, val label: String, val sources: List<Source>)

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

`occurrenceKey` is stable: `"P:{policyId}:{ruleId}:{date}"`, `"O:{oneOffId}"` or `"S:{epochMillis}"` (snooze), so skips/logs/snooze can refer to occurrences.

Also in the engine:
- `ScheduleEngine.between(snapshot, fromInclusive, toInclusive, zone)` — every occurrence in a window (used to find overdue alarms).
- `Timeline.build(snapshot, from, zone, days)` — Home's day-by-day list, with skipped policy alarms as separate flagged entries.
- `ArmPlanner.decide(snapshot, zone, now, previous, snooze, isHandled)` → `ArmDecision(armed, triggerAt, missed)`. Overdue = occurrences between the previously armed time and now, plus a past snooze, minus handled ones. The earliest overdue one no older than `LATE_RING_WINDOW` (30 min) is caught up at now + 1 s; older ones are returned as missed. Otherwise the earlier of the next occurrence and a future snooze is armed. `BACKUP_DELAY` = 2 min.

## Android layer

### AlarmScheduler
Single entry point `rearm(reason)`, called after any data change, after firing, and from all system receivers. Under a Mutex (idempotent, any thread) it:
1. Asks `ArmPlanner.decide` with the persisted armed alarm, the snooze (an extra pending instant in DataStore) and the fire log.
2. Records each missed occurrence (MISSED row + "Missed alarm" notification); clears a missed snooze.
3. Restores the ring guard if its persisted owner is still unresolved (see Firing path, force-stop recovery).
4. Only then cleans up: past one-offs/skips before the decision window, fire-log rows older than 60 days — data the decision needed must still exist while deciding.
5. Arms primary + **backup** with `setAlarmClock` (fixed request codes) and persists the armed alarm in DataStore — only if AlarmManager accepted it.
6. After releasing the lock, requests a widget update. The widget renders the persisted armed state (`SchedulerState.armedFlow`), never computes on its own.

### Firing path
Code: `AlarmReceiver` (thin), `RingingService`, `AlarmFiring` (everything the path persists), `RingDecisions` (pure decisions).

**1. Receiver starts the service synchronously.** `AlarmReceiver` (exported=false, directBootAware) decodes the armed alarm, takes a 60 s partial wake lock (`FiringWakeLock`, released by the service once it holds its own, or after the receiver's fallback work) and, before any database work (the alarm's foreground-service start exemption is short), calls `startForegroundService(RING)`. Success = no exception and a non-null `ComponentName`; the receiver then returns and the service does the rest. On failure it goes async (`goAsync`) and: claims the occurrence (so the planner does not catch it up in a loop), posts the **fallback alarm notification**, arms the **ring guard** with attempt 1, consumes the snooze if it was one, and always re-arms. If the claim shows the occurrence was already handled, nothing is posted.

**2. Service goes foreground first, then claims and rings.** On RING `RingingService` calls `startForeground` (type `systemExempted`; on 31–33 the platform only checks the type against the manifest) before anything else — with the current alarm's notification if one already rings, otherwise a minimal notification without a full-screen intent, so a duplicate delivery never flashes the ringing screen; the full notification replaces it once the delivery is known to ring (and is itself retried as the minimal one if it throws). If that also fails it posts the fallback, arms the guard and only then stops (residual risk: the system may still crash the process for not calling `startForeground`; the fallback notification and the guard alarm survive that). Then, serialized per delivery:
- `claim` (5 s timeout). A delivery for the occurrence already ringing in this process just keeps it. A guard re-ring or a **backup** delivery of an already-claimed occurrence rings while its fire-log outcome is still FIRED (whoever claimed it may have died before arming the guard); other later deliveries (catch-up) are quiet duplicates. Every duplicate still calls `rearm`. A failed or timed-out claim rings anyway — ringing twice beats not ringing.
- Ringing: `MediaPlayer` with `USAGE_ALARM` (chosen tone → `RingtoneManager.getDefaultUri(TYPE_ALARM)` → bundled sound, which also covers the locked-boot state), prepared on `Dispatchers.IO` and started on the main thread only if that ring is still current, vibration, notification on the silent `IMPORTANCE_HIGH` alarm channel with a **full-screen intent** to `RingingActivity` and Snooze/Dismiss. Output start is a tracked job, so a newer RING or an action during settings load cannot leak a player.
- Then: arm the ring guard, cancel this occurrence's fallback notification (and the direct snooze alarm, for a snooze), consume the snooze if it is a snooze occurrence, reset the auto-snooze counter for a non-snooze occurrence, `rearm("alarm fired")`. Each step is guarded; failures are logged, never stop the ring.
- A newer alarm that starts ringing while another rings replaces it; the old occurrence is recorded `SUPERSEDED`.

**3. Ring guard.** One extra `setAlarmClock` alarm (request code 103, `ACTION_GUARD`, carrying the armed alarm and an attempt count); the guarded occurrence is persisted in DataStore. Being an alarm clock, it appears as the system's **next alarm** (status bar, lock screen) while an occurrence is ringing or unresolved; its show intent opens `RingingActivity`. While ringing, the service keeps it at now + 90 s, refreshed every 60 s, so it only fires if the service or process died. Terminal outcomes (Dismiss, Snooze or auto-snooze persisted, give-up MISSED, fallback acknowledged) cancel it. When it fires, `AlarmReceiver`:
- ignores it if this process is already ringing that occurrence;
- else, holding `FiringWakeLock` (taken synchronously before the async part, released if the service is not started), reads the outcome: still FIRED (or unreadable) and `now − at ≤ ArmPlanner.LATE_RING_WINDOW` → starts the service in guard mode (same synchronous rule); if that is refused it posts the fallback and re-arms the guard after 10 s, 30 s, then every 60 s (by attempt);
- still FIRED but beyond the window → records MISSED, posts the missed-alarm notification, withdraws the fallback notification (so a later swipe cannot turn MISSED into DISMISSED; acknowledging a fallback never overwrites MISSED anyway), cancels the guard;
- terminal outcome → nothing (clears the guard).
The receiver checks the fire log itself (rather than starting the service in guard mode blindly) because only it applies the late-window give-up, and a stale guard then never shows a foreground notification; the Room read is milliseconds, well inside the alarm's start exemption.

**Force-stop recovery.** A force-stop (or an OEM kill that clears alarms) deletes the guard alarm but not its persisted owner. Every `rearm()` (app open, widget update, watchdog, boot) checks it: owner still FIRED and not ringing in this process → guard re-armed at now + 10 s while within the late window, otherwise recorded MISSED with a notification, fallback withdrawn, owner cleared.

Only one occurrence is guarded at a time: arming the guard for another occurrence records a still-FIRED previous one as `SUPERSEDED` (its fallback notification, if any, stays up). Trade-off: a single fixed PendingIntent is trivially cancellable and can never accumulate stale guards, and the newest ringing alarm is the one the user must act on; the cost is that an older occurrence loses its retries once superseded, which only matters if two alarms due within minutes both fail to ring.

**4. Snooze / Dismiss.** Ignored if nothing rings or an action is already in progress. The outcome is persisted first, non-cancellably and best effort, and then the service **always** stops (output, guard refresh, foreground, `stopSelfResult(lastStartId)` so a RING queued meanwhile is never stopped from under itself). A failing disk therefore can never leave the alarm stuck ringing; instead:
- Dismiss: if the DISMISSED write fails the guard stays armed, so the occurrence rings again within 90 s rather than being lost. Any snooze that an earlier, partly failed snooze of the same occurrence left behind is withdrawn.
- Snooze (manual or auto): auto-snooze count, snooze instant, outcome, `rearm`. If any of it fails, the snooze is armed **directly** with `setAlarmClock` (request code 104, a normal FIRE delivery deduped by the claim) so it still rings, and the guard is cancelled.
- Auto-snooze after the max ring duration (capped at 30 min in code; the wake lock lasts 40 min); after 3 auto-snoozes in a row it gives up as MISSED with a "Missed alarm" notification. The count is also kept in memory, so the cap holds even when it cannot be persisted (within one process lifetime).
Stopping only clears the ringing state if it still refers to the same ring.

**Fallback alarm notification.** Channel `alarm_fallback_v2` (the old `alarm_fallback` is deleted on start, since channel sounds are frozen at creation; `IMPORTANCE_HIGH`, sound = bundled `android.resource://<pkg>/raw/fallback_alarm` with `USAGE_ALARM` — deterministic and readable before the first unlock, vibration, public on lock screen) lets a notification ring by itself: category ALARM, `FLAG_INSISTENT`, re-posts do not restart the sound. Its full-screen/content intent opens `RingingActivity` in **fallback mode** (directBootAware; label, time and a Dismiss button that sends `ACTION_FALLBACK_DISMISS`). Dismiss action, swipe and that button record DISMISSED, cancel the guard and the notification. Posted when the service cannot be started, cannot go foreground, or cannot play any sound (then it keeps vibrating regardless of the setting).

`RingingActivity` (normal mode): shows over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`), big time + label, Dismiss and Snooze. If the full-screen intent is denied, the service still rings and the heads-up notification has Snooze/Dismiss. Opening the app while an alarm rings brings `RingingActivity` up (covers notifications being disabled). `Notifier` logs every notification it has to drop.

**Android 12–13.** Exact alarms use `SCHEDULE_EXACT_ALARM` (`maxSdkVersion` 32, granted by default there) and `USE_EXACT_ALARM` from 33. Code stays within API 31 (lint `NewApi` must be clean); scheduler and firing tests run under Robolectric on SDK 31, 33 and 35. If `setAlarmClock` throws `SecurityException`, the alarm is not recorded as armed and a "could not be scheduled" notification is posted.

### System receivers → `rearm()`
`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. Each also (re)schedules the watchdog once the user is unlocked.

## Reliability (critical requirement)

Losing a wake-up is the worst failure. Defense in depth:

1. **`setAlarmClock()` only.** It is the strongest AlarmManager API: exempt from Doze and App Standby, fires exactly, and is the same mechanism stock clock apps use. Never use `setExact`/`setAndAllowWhileIdle`/WorkManager for the actual alarm.

2. **Direct Boot support.** App components involved in firing are `directBootAware`, and **all** data (Room DB and DataStore) lives only in device-protected storage — no mirroring — so alarms re-arm and ring after a reboot *before* the phone is unlocked (e.g. overnight OS update). Single-user personal app; data is not sensitive. DataStore replaces a corrupt file with defaults rather than failing. WorkManager (credential-protected) is only touched once the user is unlocked.

3. **Backup alarm.** Alongside the primary, arm a second `setAlarmClock` alarm (different request code) at `next + 2 min`. The primary's re-arm moves it to the next alarm; if the primary was swallowed, the backup rings with a "backup" label; if both arrive, the fire-log claim makes the second a quiet duplicate.

3b. **Ring guard.** A third `setAlarmClock` alarm that exists only while an occurrence is claimed but unresolved (see Firing path). It is what turns "claimed" into "will ring again unless handled": a process killed mid-ring, a refused or failed service start, or a crash before ringing all end in a re-ring (or, after the 30-min late window, a missed-alarm notification) instead of silence. It replaces the earlier release-the-claim/catch-up loop, which retried every few seconds for 30 minutes.

4. **Watchdog / self-healing.** `rearm()` is also triggered by:
   - app open, widget update, and a periodic `WorkManager` job (every ~6 h) as a cheap safety net;
   - each run simply re-arms (idempotent: `setAlarmClock` with the same PendingIntent replaces the old one) and logs the reason.

5. **Missed-alarm detection.** On any `rearm()`, overdue occurrences without a FireLog row and older than the late window are recorded MISSED with a high-priority "Missed alarm at 07:55" notification; the ring guard does the same for claimed occurrences that were never resolved — so failures are visible, never silent.

5b. **Residual ways to lose an alarm** (accepted, documented): force-stop (clears all alarms until Reveille is opened again); notifications disabled *and* the service refused (the fallback cannot be shown — logged, and the guard keeps retrying the service for 30 min); the process dying in the milliseconds between committing a claim and arming the guard; the OS refusing `setAlarmClock` itself; the guard alarm's time appearing as the "next alarm" in the status bar while an alarm is unresolved (cosmetic); a dismissal that cannot be written re-rings every 90 s until the late window ends; a snooze withdrawn by a dismissal whose DataStore write also fails still rings; with storage failing, the auto-snooze cap resets if the process dies between snoozes.

6. **OEM setup checklist (HyperOS/MIUI first-class).** A "Reliability" screen; while anything is not green, Home shows a (non-blocking) banner linking to it and the widget a warning icon:
   - Notifications permission (`POST_NOTIFICATIONS`) — granted?
   - Full-screen intent (`NotificationManager.canUseFullScreenIntent()`, Android 14+) — granted? Deep link to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
   - Exact alarms (`canScheduleExactAlarms()`) — should always be true with `USE_EXACT_ALARM`; checked anyway.
   - Battery optimization ignored (`isIgnoringBatteryOptimizations`) — request via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (allowed for sideloaded app).
   - Alarm channel not muted / importance high.
   - Alarm stream volume above zero.
   - **Xiaomi/HyperOS specific** (detected via `Build.MANUFACTURER` / `Build.BRAND`: Xiaomi, Redmi, POCO), manual steps with deep links where available, falling back to app details screen:
     - Autostart ON (`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`) — without it `BOOT_COMPLETED` is not delivered.
     - Battery saver → "No restrictions" (`com.miui.powerkeeper/.ui.HiddenAppsConfigActivity`).
     - "Show on lock screen" and "Display pop-up windows while running in background" permissions (`com.miui.securitycenter` permission editor `miui.intent.action.APP_PERM_EDITOR`).
     - Advice: lock the app in Recents.
     These cannot be read programmatically, so each has a "I've done this" checkbox plus a **Test alarm** button.
   - Generic fallback links for Samsung/OnePlus/Oppo/Huawei pointing at app battery settings (based on dontkillmyapp.com guidance).

7. **Test alarm.** Creates an additive one-off labelled "Test alarm" at the next whole minute at least a minute away (so it rings in 1–2 min), usable with the screen off — the user validates the whole path end to end after setup; the result shows in the alarm log.

8. **Force-stop caveat.** A user/system force-stop clears all alarms and the watchdog job, and a force-stopped app receives no boot broadcast, so restarting the phone does not help. Alarms come back the next time Reveille is opened, e.g. by tapping its widget (Android does not send widget updates to a force-stopped app); missed-alarm detection and the guard restore then report or re-ring what was lost. Documented in the Reliability screen.

## Widget (Glance)

- Shows next occurrence: `Thu 08:55 · Work` and relative time ("in 19 h"). Empty state: "No alarms".
- **Skip** button: skips the displayed alarm only if it is still the armed one (a stale tap is a no-op): a policy alarm skips that policy's **whole day** (Skip row), a one-off is deleted, a snooze is cleared; a past catch-up is logged SKIPPED so it does not ring again; then `rearm()`.
- Tap anywhere else → opens app.
- Updated by `rearm()`; also `updatePeriodMillis` = 30 min, and each widget update re-arms as an extra safety net (not delivered while the app is force-stopped). Renders the persisted armed state, never computes on its own.
- If the reliability checklist is not green, widget shows a small warning icon.

## Screens (Compose)

1. **Home / Next up**: reliability banner (if needed), next-alarm card, timeline of the next 14 days grouped by day with Skip/Unskip (and Edit for one-offs) buttons; "New policy" and "New alarm" buttons; links to Policies and Settings.
2. **Policies**: list with enable toggles; editor with name, start/end dates, rules (time, weekday chips, every N weeks + anchor), pauses list.
3. **One-off editor**: date, time, label, Replace day / Ring in addition toggle; shows which policy alarms would be replaced.
4. **Settings**: ringtone, vibration, snooze length, max ring duration, volume ramp.
5. **Reliability**: checklist, test alarm, recent FireLog entries.

## Error handling

- Invalid input prevented in editors (rule with no weekdays, endDate < startDate, pause from > to).
- Engine horizon capped at 400 days; empty result → widget "No alarms", primary/backup cancelled.
- All DB writes go through a repository that calls `rearm()` afterwards in the same coroutine; failures surface as snackbars.
- Late firing (e.g. after reboot past the alarm time): if within 30 min of scheduled time, ring anyway (re-armed via `setAlarmClock` at now + 1 s so the foreground-service start stays exempt); otherwise mark missed and notify. Every delivery claims its occurrence in FireLog (unique key + time), so primary, backup and catch-up never ring twice; only the ring guard re-rings a claimed occurrence, and only while it is unresolved.

## Testing

- **Engine unit tests (JVM, extensive)**: single rule; multiple rules in one policy; every-N-weeks with anchors; start/end windows; pauses; skips; REPLACE_DAY vs ADDITIVE; multiple one-offs same day; duplicate instants; DST gap/overlap (Europe/Rome); time zone change; horizon/empty.
- **Repository/DAO tests**: Room in-memory tests under Robolectric (no emulator needed).
- **Scheduler tests**: Robolectric with shadow AlarmManager (SDK 31, 33, 35) — verifies primary + backup armed, replaced on change, cancelled when empty, guard arm/refresh/cancel/supersede, guard restore after a simulated force-stop, fire-log pruning, refused exact alarms not persisted.
- **Firing tests**: `RingDecisions` (pure JVM), `AlarmFiring` (refused start → claim + fallback + guard; guard ring/back-off/give-up/no-op; duplicates; snooze/dismiss persistence), `RingingService` via `ServiceController` (rings and guards, snooze persisted before stopping, duplicates do not ring, supersede) and `AlarmReceiver` (synchronous start) — all on SDK 31, 33, 35.
- **Manual on device (HyperOS phone)**: test alarm with screen off; reboot before unlock; airplane mode; battery saver on; overnight real alarm.

## Project layout

```
:app      Android app (UI, Room, receivers, services, widget)
:engine   Pure Kotlin scheduling engine + tests
```
