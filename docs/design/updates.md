# In-app updates from GitHub Releases: spec

Reveille is sideloaded, so it checks GitHub Releases itself. It shows what's new, downloads the signed APK, verifies it, and hands it to Android's installer.

Design lives on the canvas (https://claude.ai/artifact/9ZYXbAyXxZyZL3u5RVp2pu, row "Reveille — updates from GitHub"). The mockups are in `docs/design/mockups/Update*.dc.html`. Tokens and components are the ones in `docs/design/README.md`. The only new token is `AccentTint` (`#1A1810`), the fill of accent notices, which the mockups already used.

| Mockup | What it shows | Where it lives |
|---|---|---|
| `UpdateSettings.dc.html` | **Updates** section in Settings (above About) | `ui/settings/SettingsScreen.kt` |
| `UpdateToday.dc.html` | Slim update card under the next-wake-up card | `ui/home/HomeScreen.kt` |
| `UpdateSheet.dc.html` | Release sheet: version, notes, trust lines, actions | new `ui/update/UpdateSheet.kt` |
| `UpdateStates.dc.html` | Footer states of the sheet: downloading, verified, permission, alarm guard, failure | same sheet |

---

## 1. Rules

1. **The one rule still wins.** Update code never runs on, blocks or delays the alarm path. Nothing in `alarm/`, `engine/` or the ring/arm flow may depend on `update/`.
2. **Never install while an alarm is ringing, snoozed-and-due, or due within 30 minutes.**
   - While an alarm is ringing, Install is not offered at all.
   - Within the 30-minute window the sheet shows the alarm guard state. "Install anyway" is allowed there, but only when nothing is ringing.
3. **Only install what we can prove is ours.** All four of these must hold:
   - the APK's SHA-256 matches the release's `.sha256` asset;
   - the APK's signing certificate equals the installed app's;
   - the APK's package name equals ours;
   - its `versionCode` is greater than `BuildConfig.VERSION_CODE`.
   If any check fails, delete the file, show "Checksum didn't match", "Signature didn't match" or (wrong package or version) "Package didn't match", and install nothing. An unreadable `.sha256` asset counts as a checksum failure, and the APK is not downloaded.
4. **After install, alarms must be re-armed.** `SystemEventsReceiver` already handles `MY_PACKAGE_REPLACED`. Add a Robolectric test proving that path re-arms, and keep it working.
5. **Debug builds don't self-update.** Their signing key differs, so the install would always fail. Turn off the automatic check and show the Settings row as "Updates are off in debug builds". A manual "Check now" may still show the release, but the action becomes "Open on GitHub".
6. **WorkManager only when the user is unlocked** (existing AGENTS.md invariant). The update worker never runs in Direct Boot.

## 2. Data source

- Request: `GET https://api.github.com/repos/replydev/Reveille/releases/latest`.
  - Send the headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28` and `User-Agent: Reveille/<version>`.
  - The request is unauthenticated, which allows 60 requests per hour per IP; one check a day is far below that.
  - Store the `ETag` and send `If-None-Match`; a `304` means nothing changed.
- `/latest` already excludes drafts and pre-releases. Ignore any release whose tag doesn't match `^v(\d+)\.(\d+)\.(\d+)$`.
- **Assets** are published by `.github/workflows/release.yml`:
  - `reveille-v{X.Y.Z}.apk`. Use its `size` field for the size shown on the sheet (the `[SIZE]` placeholder in the mockup), formatted as e.g. "6.1 MB".
  - `reveille-v{X.Y.Z}.apk.sha256`. Its format is `sha256sum` output: `<64 hex>  reveille-vX.Y.Z.apk`. Parse the first token and require 64 hex characters.
  - Download through each asset's `browser_download_url`, following redirects.
- **Version** comes from the tag, via the same formula as the build: `versionCode = major × 1,000,000 + minor × 1,000 + patch`. Compare it with `BuildConfig.VERSION_CODE`.
- **Release notes** are the `body` field, written by `scripts/semver.sh notes`.
  - Keep the sections `## Breaking changes`, `## Features`, `## Fixes` and `## Performance`, and drop `## Install`.
  - Each section's `- ` bullets become plain lines: markdown links and backticks removed, the commit prefix (`feat(ui): `) and the trailing pull request reference (`(#7)`) dropped, and the first letter capitalised, as in `UpdateSheet.dc.html`.
  - Show at most 6 bullets per section, then "and N more", with "On GitHub" opening `html_url`.
  - If there are no sections, show the first 3 non-empty lines outside `## Install`.
  - The one-line summary (Today card, notification) is the first Features bullet, else the first Fixes bullet, else the first bullet of any section.
- Use `HttpURLConnection` and `org.json`. **Don't add a networking or JSON library.** Timeouts are 10 s to connect and 20 s to read.

## 3. Architecture (new package `update/`)

- `ReleaseSource` (interface) → `GitHubReleaseSource`. It returns `Release(tag, versionCode, publishedAt, notes, apkUrl, apkSize, sha256Url, htmlUrl)`, or a typed error: `Offline`, `RateLimited`, `Http(code)` or `Malformed`.
- `FakeReleaseSource` is used by tests and selectable in debug builds. Inject it through `AppContainer` so that Maestro can show the flow without network.
- `UpdateStore` uses DataStore and holds `autoCheck` (default on), `notify` (default on), `lastCheckedAt`, `etag`, `latest: Release?`, `skippedVersionCode`, `dismissedVersionCode`, `remindAfter: Instant?`. Credential-encrypted storage is fine here; this is not alarm data.
- `UpdateChecker.check(manual: Boolean)`: fetches, compares, stores, and posts a notification when a new release (not skipped) appears and `notify` is on.
- `UpdateWorker`: a unique periodic WorkManager job every 24 h with the constraint `NetworkType.CONNECTED`. It is enqueued only while `autoCheck` is on and the user is unlocked. It is also re-enqueued as a one-off job at `remindAfter`.
- `UpdateDownloader`: downloads to `cacheDir/updates/` with progress, streams the SHA-256 while downloading, and deletes partial files on cancel or failure.
- `ApkVerifier`: the checks from §1.3. Use `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` and compare its `signingInfo.apkContentsSigners` with the installed package's. This is API 28+, so it's fine at minSdk 31.
- `UpdateInstaller`: uses a `PackageInstaller` session (not the `ACTION_VIEW` intent).
  - Commit with a status `PendingIntent`. Handle `STATUS_PENDING_USER_ACTION` by starting the confirm intent, and map failures to a readable message.
  - If `packageManager.canRequestPackageInstalls()` is false, show the permission state, which opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:` + our package.
- `InstallGuard` (a pure function plus tests). Inputs: the armed alarm, the snooze, what is ringing, and now. "Ringing" also covers an occurrence the ring guard still protects (claimed, not yet resolved). Output is one of `Allowed`, `AlarmSoon(armed)` (the earlier of alarm and snooze is due ≤ 30 min, or overdue) or `Ringing(armed)`. The sheet checks it when "Download and install" is tapped and again on "Install".
- `UpdateUiState` (a sealed type) drives the sheet footer: `Idle(release)`, `Downloading(progress)`, `Verifying`, `Ready`, `NeedsPermission`, `AlarmSoon(label, at)`, `Failed(reason)`, `Installing`.

**Manifest:**
- add the permissions `INTERNET`, `ACCESS_NETWORK_STATE` and `REQUEST_INSTALL_PACKAGES`;
- add a new notification channel `updates` with LOW importance, kept separate from the alarm and fallback channels;
- `RingingActivity` is untouched.

## 4. Screens (match the mockups)

### Settings › Updates (`UpdateSettings.dc.html`)
- **Status row (first).** Three variants:
  - *Update available*: an `Accent` 40 dp icon tile with a download icon, "vX.Y.Z is available", "You have vA.B.C · released d MMM", and a "View" pill that opens the sheet. The row background is `AccentTint` (`#1A1810`).
  - *Up to date*: the same row without the tint. Use a `Success` check tile and "You're up to date · vA.B.C".
  - *Debug*: a muted tile with "Updates are off in debug builds".
- A skipped release still shows here as *Update available* (Skip only silences the notification, the Today card and the nav dot), so it can be installed later.
- **Check automatically** switch, subline "Once a day, on any network". Turning it off cancels the worker.
- **Notify me** switch, subline "A quiet notification when a release is found".
- **Last checked** row with a relative/day time ("Today, 09:12", or "Never") and an outlined "Check now" pill.
  - While checking, the pill shows a spinner and the text "Checking…".
  - Errors appear as a snackbar: "No connection.", "GitHub is busy. Try again later." or "Couldn't read the release."
- A 13 sp `Muted` footnote naming `github.com/replydev/Reveille` (mono) and the SHA-256 and same-key rule.
- **About** row: the Daybreak mark tile, "Reveille", the version in mono, and "Release notes" on the right, which opens the current version's GitHub release page.
- **Nav badge:** an 8 dp `Accent` dot on the Settings tab while an unskipped, undismissed update exists. It sits below the Health `Danger` dot in priority; if both apply, show only Health's dot on Health.

### Today notice (`UpdateToday.dc.html`)
- When an update exists that is not skipped or dismissed, show a slim card under the next-wake-up card:
  - `AccentTint` fill, `AccentLine` border, radius 20;
  - an `AccentContainer` icon tile;
  - "Update ready: vX.Y.Z" (14/600) above the first feature or fix bullet, ellipsized;
  - "View" in `Accent`, plus a 40 dp × button that sets `dismissedVersionCode`.
- Never show it while the reliability pill shows a failure, so that Health always wins the user's attention. Never show it within 30 minutes of an alarm.

### Release sheet (`UpdateSheet.dc.html`)
- A `ModalBottomSheet`, the same component as the one-off sheet (top at about 96 dp).
- **Header:**
  - a 56 dp Daybreak tile;
  - the overline "UPDATE AVAILABLE" in `Accent`;
  - the version at 34 sp/600;
  - "From vA.B.C · released d MMM · {size}";
  - a close button.
- **What's new** card: section titles at 13/600, bullets at 14 sp with a 6 dp dot (`Accent` for Features and Breaking changes, `Muted` for the others), and "On GitHub" in the top-right of the card.
- **Two trust lines**, each with a `Success` icon:
  - **Verified before install.** The APK must match its published SHA-256 and be signed with this app's key.
  - **Your alarms stay set.** Installing restarts Reveille; it re-arms every alarm as soon as the update is in.
- **Footer** (state-driven, §3):
  - In `Idle`, a full-width 56 dp `Accent` "Download and install" button, with "Later" (closes; the card stays) and "Skip vX.Y.Z" (sets `skippedVersionCode`, closes) as a two-column text-button row.
  - The other states replace the footer with the matching card from `UpdateStates.dc.html`.

### Footer states (`UpdateStates.dc.html`)

| State | Content | Actions |
|---|---|---|
| Downloading | "Downloading…" with % in mono, a 6 dp `Accent` progress bar, "Then verify checksum and signature" | Cancel |
| Verifying | Same card, indeterminate bar, "Verifying…" | none |
| Ready | `Success` shield-check, "Verified", "SHA-256 and signing key match" | **Install** (`Accent` pill) |
| NeedsPermission | Lock icon in `Accent`, "Allow Reveille to install apps", "Android asks once. Turn on "Allow from this source", then come back." | Open settings (outlined). On resume, re-check and go to Ready |
| AlarmSoon | `AccentTint` card, `AccentLine` border, clock icon, "{label} rings in N min", "Updates wait until it has rung. We'll remind you after HH:MM." | **Remind me** (sets `remindAfter` = alarm + 5 min, closes), Install anyway (hidden while ringing) |
| AlarmSoon while ringing | Same card, "{label} is ringing" | **Remind me** only |
| Failed | Danger card, warning icon, "Checksum didn't match", "Signature didn't match" or "Download failed", body "The download was deleted and nothing was installed. Your alarms are unaffected." | Try again |
| Installing | System installer UI | (none) |

### Notification
- Channel `updates`, importance LOW, small icon: the Daybreak monochrome glyph.
- Title "Reveille vX.Y.Z is available"; text: the first bullet.
- Tapping it opens the app straight into the release sheet.
- One notification per version, cancelled when the user opens the sheet, skips or installs.

## 5. Tests
- **Unit tests:**
  - tag parsing and versionCode;
  - the `.sha256` parser, with good, short, uppercase and garbage input;
  - the notes parser on real `semver.sh` output, including the dropped `## Install` section and the "and N more" truncation;
  - `InstallGuard` at the edges: 30 min exactly, a snooze, ringing, and no alarm;
  - the skip and dismiss logic;
  - the `UpdateUiState` transitions.
- **Robolectric tests:**
  - `UpdateChecker` with `FakeReleaseSource` (new version, same version, 304, rate-limited, offline);
  - the notification is posted once per version;
  - the worker is enqueued and cancelled when the toggle changes;
  - `MY_PACKAGE_REPLACED` re-arms (§1.4).
- **Verifier:** test it with two tiny signed test APKs under `app/src/test/resources/`, one with a matching and one with a mismatching cert. If that is impractical, isolate the certificate comparison behind an interface and test that.
- **Maestro:** a new flow `.maestro/update.yaml` that uses the debug `FakeReleaseSource`. Debug builds switch to it when `MainActivity` is launched with the extra `fake_updates=true` (Maestro `launchApp` `arguments`); release builds ignore the extra. The emulator reports failing Health checks, so the Today card's visibility rule (`showUpdateNotice`) is covered by a unit test rather than the flow. It goes: Settings → status row → sheet → Skip, then the card is gone. It must not need the network.
- **Can't be proven on an emulator:** the real install over an existing release build, and alarms ringing after the update. Add these to the device checklist in `docs/reports/`.
