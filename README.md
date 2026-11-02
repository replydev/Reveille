# Reveille

An Android alarm clock for schedules the stock Clock app can't express, built so that a wake-up is never lost silently.

## Features

- **One-off alarms on a date.** "09:30 next Thursday only", with no recurrence. A one-off can either replace that day's recurring alarms or ring in addition to them.
- **Policies.** A named group of recurring rules, for example *Work* = 07:55 Mon–Wed + 08:55 Thu–Fri. Each rule can repeat every N weeks. A policy can have start/end dates and pause ranges (holidays), and you can skip a single day.
- **Home timeline.** The next 14 days at a glance, with Skip, Unskip and Undo.
- **Widget.** Shows the next wake-up, with a Skip button.
- **Reliability.**
  - Uses `setAlarmClock` (the same API the stock Clock app uses) and arms a backup 2 minutes after every alarm.
  - A ring guard re-rings if the app is killed mid-alarm.
  - A fallback notification rings by itself if the ringing service can't start.
  - Alarms work after a reboot, even before the first unlock.
  - Alarms up to 30 minutes late still ring; older ones raise a "Missed alarm" notification.
  - A Reliability screen walks through Android and Xiaomi HyperOS settings and lets you ring a test alarm.

## Requirements

- Android 12 (API 31) or newer.
- Sideloaded only. It uses `USE_EXACT_ALARM`, which Google Play restricts to dedicated clock apps.

## Build and install

Prerequisites: JDK 17+ and the Android SDK (platform `android-37.0`, build-tools `37.0.0`). Point `local.properties` at the SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:installDebug          # build and install on a USB-connected phone
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug   # tests and lint
```

## First run

1. Grant notification permission when asked.
2. Open **Reliability** from Home's top bar (the red banner on Home links there too) and make every item green. On Xiaomi/Redmi/POCO phones that includes Autostart, battery saver "No restrictions", lock-screen and pop-up permissions, and locking Reveille in Recents.
3. Tap **Ring a test alarm in 2 minutes**, lock the phone and wait.

If Reveille is force-stopped, Android deletes its alarms until you open the app again (tapping the widget counts).

## Project layout

| Path | What |
|---|---|
| `engine/` | Pure Kotlin scheduling engine: occurrences, daylight-saving handling, the arm planner, the timeline. No Android dependencies. |
| `app/` | Android app: Room/DataStore storage, alarm delivery, ringing service, widget, Compose UI. |
| `docs/superpowers/specs/` | Design spec. |
| `docs/superpowers/plans/` | Original implementation plan. |
| `docs/reports/` | Build report and the on-device test checklist. |

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · DataStore · WorkManager · Glance · JUnit 4 + Robolectric.
