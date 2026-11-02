# Reveille redesign — implementation spec

The source of truth is the design canvas: https://claude.ai/artifact/9ZYXbAyXxZyZL3u5RVp2pu (it's private, so share it from its Share menu to give others access).
The exact markup for every screen is in `mockups/*.dc.html`: HTML with inline styles, plus a `renderVals()` block that holds the sample data. Read the mockup for exact px values. This file explains how to turn them into Compose.

| Mockup | Screen | Current Compose file |
|---|---|---|
| `Main.dc.html` | Today (home) | `ui/home/HomeScreen.kt` |
| `Policies.dc.html` | Policies list | `ui/policies/PoliciesScreen.kt` |
| `PolicyEditor.dc.html` | Policy editor | `ui/policies/PolicyEditorScreen.kt` |
| `OneOff.dc.html` | One-off alarm (bottom sheet) | `ui/oneoff/OneOffEditorScreen.kt` |
| `Health.dc.html` | Reliability, renamed "Health" in the nav | `ui/reliability/ReliabilityScreen.kt` |
| `Settings.dc.html` | Settings | `ui/settings/SettingsScreen.kt` |
| `Ringing.dc.html` | Full-screen alarm | `alarm/RingingActivity.kt` |
| `Widgets.dc.html` | Glance widgets | `widget/ReveilleWidget.kt` |
| `Update*.dc.html` | In-app updates from GitHub (Settings section, Today card, release sheet, states) | see `updates.md` |

Mockups are 390×844 CSS px. Treat 1 CSS px as 1 dp (and as 1 sp for text).

**Scope rule:** this is a visual and navigation redesign. Keep all behaviour, validation, copy logic, `ScreenSession` handling, and the Maestro flows' intent. The mockups use sample data; the real strings come from `TimeText`, `policyStatus`, `ReliabilityChecker` and so on.

---

## 1. Design tokens → `ui/theme/`

### Theme decision
- The app becomes **dark-first with a fixed palette**. Drop `dynamicDarkColorScheme` and `dynamicLightColorScheme` in `Theme.kt`.
- Light mode is out of scope for now. Use the dark scheme in both system modes, or add a light scheme later.

### Colors (`Color.kt`)
| Token | Hex | Use |
|---|---|---|
| `Bg` | `#0B0D11` | screen background |
| `NavBg` | `#101318` | bottom nav bar |
| `SheetBg` | `#12151A` | bottom sheet surface |
| `Surface` | `#15181E` | cards |
| `SurfaceHigh` | `#1D2129` | chips, steppers, inputs, secondary buttons, unselected day toggles |
| `Outline` | `#22262E` | card borders, dividers inside cards |
| `OutlineStrong` | `#2A2F38` | outlined buttons, switch track when off |
| `OutlineDashed` | `#3A404A` | dashed "add" buttons, sheet drag handle |
| `OnBg` | `#F3F4F6` | primary text |
| `Muted` | `#A1A9B5` | secondary text and labels (passes 4.5:1 on `Surface`) |
| `Faint` | `#7D8693` | switch knob when off, ":" separator, inactive day letters. Decorative only, never body text |
| `Accent` | `#FFB547` | primary actions, active nav, "Next wake-up", ringing background |
| `OnAccent` | `#1A1205` | text and icons on `Accent` |
| `AccentContainer` | `#2A2418` | active nav pill, icon tiles |
| `AccentLine` | `#3A3020` | border of the focused rule card |
| `AccentTint` | `#1A1810` | fill of accent notices (update card, update status row, alarm guard) |
| `Danger` | `#FF7A66` | dot badges, failing segment |
| `DangerText` | `#FF8A75` | warning text and icons, danger button background |
| `DangerContainer` | `#1E1513` | warning card and pill background |
| `DangerLine` | `#3A2A25` | warning card border |
| `DangerOnContainerMuted` | `#D8C2BC` | body text inside the warning card |
| `OnDanger` | `#1E0D09` | text on the `DangerText` button |
| `Success` | `#7EE0C3` | passing check marks and health bar segments |
| `WidgetWallpaper` | `#2B3242` | used only on the widgets mockup canvas |

Map these onto a Material 3 `darkColorScheme` where it fits (`primary = Accent`, `onPrimary = OnAccent`, `background = Bg`, `surface = Surface`, `surfaceContainerHigh = SurfaceHigh`, `outline = OutlineStrong`, `outlineVariant = Outline`, `onSurfaceVariant = Muted`, `error = DangerText`, `errorContainer = DangerContainer`). Also expose a `ReveilleColors` object through a `CompositionLocal` for the extras: `Success`, `Faint`, `AccentContainer`, `Danger*`.

**Policy colours.** Each policy has its own colour. The palette is `#8AB4FF` blue, `#7EE0C3` mint, `#F2A0C8` pink, `#C9B6FF` lavender, plus `#FFB547` for one-off alarms. Tinted icon-tile backgrounds are `#1A2233`, `#15282A`, `#2A1823` and `#211C33`.
- The data model has no colour field, so derive the colour **deterministically from the policy id**: `palette[(id % 4)]`. No DB migration.
- The colour appears as an 8 dp rounded square (2–3 dp radius) next to labels, on day toggles in the policy card, and as the icon tile on the policy card.

### Typography (`Type.kt`)
- Fonts: **Geist** (300–700) for everything, and **Geist Mono** (400/500) only for technical strings such as "backup 07:57" and log timestamps. Bundle the TTFs in `res/font` (OFL, from vercel/geist-font). Don't use downloadable fonts: the ringing screen must work in Direct Boot with no network.
- All numerals are tabular: `fontFeatureSettings = "tnum"`.

| Role | Size / weight / tracking | Where |
|---|---|---|
| Display XL | 124 sp / 300 / -0.05em | ringing clock |
| Display L | 84 sp / 300 / -0.04em, line-height 0.9 | next wake-up hero time |
| Display M | 72 sp / 300 / -0.04em | one-off hour and minute boxes |
| Display S | 48–56 sp / 300 / -0.03em | rule card time, 4×2 widget |
| Title L | 28 sp / 600 / -0.02em | screen titles (Today, Policies, Health, Settings) |
| Title M | 18–20 sp / 600 | top-bar title, sheet title |
| Title S | 17 sp / 600 | policy name |
| Time row | 20–22 sp / 400 | timeline and rule-list times |
| Body | 14–15 sp / 400–500 | rows |
| Caption | 12–13 sp / 400 | secondary lines |
| Overline | 12 sp / 600 / +0.12em / UPPERCASE / `Muted` | section labels ("Alarm times", "Recent alarms", "Sound") |

### Shape and spacing
- Radii: hero card 28, cards 20–24, inputs and tiles 16, chips 8, day toggles 12, pills and buttons fully rounded (height/2), sheet top corners 32, FAB 18.
- Screen horizontal padding is 16 for cards and 20 for headers. The gap between cards is 10–12, and section gaps are 22.
- Touch targets are at least 40 dp, and 44–48 dp for primary controls.
- Cards are **flat**: a 1 dp `Outline` border and no elevation. Only the FAB has a shadow.

### Icons
The mockups use inline stroke SVGs (2 dp stroke, round caps). Recreate them as hand-written vector drawables (`res/drawable/ic_*.xml`). Don't add the Material icons artifact. Every icon-only button needs a `contentDescription`. The Material Symbols names are for reference only:

| Use | Icon |
|---|---|
| Today | alarm |
| Policies | calendar_month |
| Health | shield |
| Settings | tune |
| Warning | warning |
| Delete | delete |
| Back | arrow_back_ios |
| Label | label |
| Sound | music_note |
| Add | add |
| Close | close |
| Check | check |
| Ringing chip | notifications |

No emoji. The `✓` / `✗` text glyphs in ReliabilityScreen are replaced by icons.

---

## 2. Navigation (`ReveilleNavHost.kt`)
- **Replace the top-bar TextButtons with a bottom `NavigationBar`** that has four destinations: Today (`home`), Policies (`policies`), Health (`reliability`) and Settings (`settings`). The bar is 84 dp tall, uses `NavBg` with a top 1 dp `SurfaceHigh` divider, and has labels at 12 sp.
- The active item gets a 56×30 pill in `AccentContainer` with the icon in `Accent`, and the label in `OnBg`. Inactive items are `Muted`.
- The Health item shows a 8 dp `Danger` dot badge when any reliability check fails (reuse the `failingChecks` logic from HomeScreen).
- The four tabs are top-level with no back arrow. The editors (`policy/{id}`, `oneoff/{id}`) are pushed screens without the bottom bar. Keep the shared-axis motion for pushes; switching tabs should use a fade-through or no animation.
- **One-off editor becomes a `ModalBottomSheet`.** The sheet starts about 120 dp from the top, uses `SheetBg`, has a 32 dp top radius and a 40×4 handle. It can stay a nav destination (`dialog`/bottomSheet route) so that `SavedStateHandle` and `ScreenSession` keep working.

---

## 3. Screens

### Today (`Main.dc.html`)
1. **Header** (padding 52/20/12):
   - On the left, the date in 13 sp `Muted` ("Monday, 2 November") above the title "Today".
   - On the right, a **reliability pill** that is shown only when checks fail: 40 dp tall, `DangerContainer` fill, `DangerLine` border, `DangerText` warning icon and the text "N issue(s)". Tapping it opens Health.
   - This pill replaces the full-width error `ReliabilityBanner` card. Keep the "could not run" case: same pill, text "Check failed".
2. **Next wake-up hero card** (`NextUpCard`): `Surface`, radius 28, padding 22, 1 dp `Outline` border.
   - Top row: the overline "NEXT WAKE-UP" in `Accent`. On the right, an 8 dp `Accent` dot with "Armed with system" in `Muted`.
   - Time in Display L, and to its right "Tomorrow" (17 sp/500) above the relative time (14 sp `Muted`).
   - Chips (28 dp, `SurfaceHigh`, radius 8): the policy colour square plus the label, then a mono chip "backup HH:MM" (armed time + 2 min).
   - Buttons (48 dp pills, equal width): "Skip this one" (outlined) and "Open policy" (`SurfaceHigh`). For a one-off, the second button reads "Edit".
   - States: **None** shows the time slot as "None scheduled". **Overdue/Ringing** changes the overline to "RINGING NOW" or "OVERDUE". **NotSet** and the earlier-unarmed warning replace the "Armed with system" dot with a `DangerText` warning line under the chips. Keep the 3 s grace logic.
3. **"Next 14 days"** header (15 sp/600) with the alarm count on the right in `Muted`.
4. **Timeline** is grouped by date. Each group is a row with a 1 dp `SurfaceHigh` top divider and 12 dp vertical padding.
   - Left column (44 dp wide, centred): 11 sp uppercase weekday in `Muted` above a 20 sp day number.
   - Right column: one row per occurrence (min 40 dp). Each row has the time (20 sp, 62 dp wide), the policy colour square, the label, an optional note in 12 sp `Muted` ("every 2 wk", "one-off", "skipped"), and trailing text buttons in `Muted`: "Skip" / "Unskip", plus "Edit" before "Skip" on one-off rows.
   - Skipped rows use 45 % alpha with the time struck through.
   - A one-off row's colour is `Accent`.
5. **FAB**: extended, 56 dp, radius 18, `Accent` fill, "+ One-off alarm". It sits 16 dp above the nav bar.
   - The secondary "New policy" FAB is removed from Today because it now lives on the Policies tab.
6. Empty state: a muted text line in place of the timeline (keep the current copy).

### Policies (`Policies.dc.html`)
- Header: the title plus a 14 sp `Muted` intro line: "Named groups of recurring alarms. Turn a whole routine on or off in one tap."
- **Policy card**: `Surface`, radius 24, padding 18, gap 14, 1 dp `Outline` border. The whole card is clickable.
  - Top row: a 36 dp icon tile (policy tint with a 12 dp policy-colour square), the name (17/600), and the status line below it (13 sp).
  - Status comes from `policyStatus()`. When it returns null, show "Active · next {day HH:MM}". Pause statuses use `Accent` and everything else uses `Muted`.
  - A `Switch` sits on the right. Its colours: checked track `Accent` with an `OnAccent` thumb; unchecked track `OutlineStrong` with a `Faint` thumb.
  - Divider, then one row per rule: the time (22 sp), then **7 mini day cells** (20×22, radius 6, letters M T W T F S S). Active cells use the policy colour with `Bg` text; inactive cells use `SurfaceHigh` with `Faint` text. A right-aligned note shows "every N wk" and/or the label.
  - A disabled policy is drawn at 60 % alpha.
- FAB "+ New policy" in the same style as on Today.

### Policy editor (`PolicyEditor.dc.html`)
- **Top bar**: back, the title "Edit policy" / "New policy", and a delete icon button (only when editing). The delete confirm dialog stays the same.
- **Name**: a filled field (`Surface`, radius 16, label "Name" at 12 sp `Muted`, value 18/600). Beside it, an 84 dp-wide "Enabled" tile with a small switch.
- **Active from / Active until**: two equal tiles in a grid (label plus value). The value is "Always" when null. Tapping opens the existing date picker; keep the existing clear behaviour from `OptionalDateRow`.
- The "ALARM TIMES" overline.
- **Rule card**:
  - Expanded state: `Surface`, radius 20, `AccentLine` border. It contains the time as Display S (tap to open the time picker), a 40 dp round remove button, a 7-column grid of day toggles (40 dp tall, radius 12, selected `Accent`/`OnAccent`, unselected `SurfaceHigh`/`Muted`), and a "Repeat every" row with a pill stepper (− [N week(s)] +, 40 dp). When N > 1, keep the animated "Including the week of" date row. The label field is 44 dp, `SurfaceHigh`, radius 12, with a label icon.
  - Collapsed state (rules other than the focused one): a single row with the time at 28 sp/300, the day summary from `TimeText.days`, and a chevron. Tapping it expands that rule and collapses the others. If that is too much churn, it's fine to keep every rule expanded.
- **Add buttons**: "+ Alarm time" and "+ Pause" as two equal 44 dp dashed-outline pills.
- **Pause row**: `Surface`, radius 14, calendar icon, "Paused {from} → {to}" (tap a date to edit it), and a Remove text button.
- **Sticky bottom bar** (`Bg`, top divider): on the left, "Next: {day, HH:MM}" from the engine, or the `upcomingWarning` text in `Accent` and errors in `DangerText`. On the right, the **Save** pill (52 dp, `Accent`).
  - This replaces the Save TextButton in the top bar. It is disabled under the same conditions as today.

### One-off alarm sheet (`OneOff.dc.html`)
- The title "One-off alarm" / "Edit alarm" and a close button. The delete icon goes in the header when editing.
- **Time**: two 120×104 boxes (`SurfaceHigh`, radius 20) for hour and minute, with a ":" between them. The focused box gets a 2 dp `Accent` border. Tapping opens the existing time picker; inline wheel input is a possible later enhancement.
- **Date**: the "DATE" overline, then horizontally scrolling 56×68 day tiles (weekday overline plus day number). Show today + 7 days, matching the current `QuickDates`. Selected is `Accent`; the rest are `SurfaceHigh`. The last tile is a dashed calendar tile that opens the date picker.
- **Label** field: 52 dp, `SurfaceHigh`, radius 16, with a label icon.
- **Mode**: the overline "ON {DAY DATE}", then a **segmented control** with "Replace the day" / "Ring in addition". The selected segment is `OnBg` fill with `Bg` text.
- **Impact list**: the policy alarms on that date (`ScheduleEngine.policyAlarmsOn`). Each row shows the time (struck through when replacing), the policy colour and name, and "won't ring" or "also rings". Keep the "another alarm already replaces this day" message.
- The past-time error goes in `DangerText` above the button.
- **Primary button**: full width, 56 dp, `Accent`, text "Set for {Day d MMM}, HH:MM".

### Health (`Health.dc.html`)
- The title "Health", then the summary "X of Y checks pass". On the right, "Alarms may not ring" in `DangerText`; when all pass, show "All good" in `Success` instead.
- A **segmented bar**: Y segments, 6 dp tall with 4 dp gaps. Passing segments are `Success` and failing ones `Danger`.
- **Failing checks first**, each as a card (`DangerContainer`, `DangerLine` border, radius 20). A card has a 32 dp warning tile, the title (16/600), the detail text (13 sp, `DangerOnContainerMuted`), and an "Open settings" pill (`DangerText` fill, `OnDanger` text). Manual checks also get a "Mark as done" checkbox.
- **Passing checks**: one grouped card with 44 dp rows, each a `Success` check icon plus the title. A trailing `Muted` note says "HyperOS" for OEM checks or "Confirmed" for manual ones; tapping a manual one lets you un-confirm it.
- **Test alarm card**: the title "Ring a test alarm", the subline "In 2 minutes. Lock the phone and wait.", and a "Start" pill. The result still appears as a snackbar.
- Keep the force-stop explanation as a 13 sp `Muted` footnote. It isn't drawn in the mockup.
- "RECENT ALARMS" overline, then log rows (36 dp): a mono timestamp in `Muted`, the label, and the outcome. "missed" uses `DangerText`; the others use `Muted`.

### Settings (`Settings.dc.html`)
- Grouped cards with overlines: **Sound**, **Snooze** and **About**.
- Sound card:
  - "Alarm sound" row: an `AccentContainer` icon tile, the ringtone name in `Muted`, and a chevron. It opens the system picker.
  - Two switch rows with sublines: "Vibrate" (*Pulse while the alarm rings*) and "Gradually increase volume" (*Starts quiet, reaches full volume*).
- Snooze card: two rows with pill steppers (44 dp): "Snooze length" and "Auto-snooze after ringing for".
- About card: an app-icon tile and "Reveille" with the version (`BuildConfig.VERSION_NAME`) in mono.

### Ringing (`Ringing.dc.html`)
- Full-bleed `Accent` background and `OnAccent` content. Keep the lock-screen and direct-boot flags.
- From the top:
  - The date overline (13 sp/600, +0.16em).
  - The live clock in Display XL.
  - A chip with a bell icon and the alarm title (`OnAccent` at 10 % alpha).
- Decoration: three concentric circles centred behind the lower half (520/360/200 dp, `OnAccent` at 10 %, 14 % and 6 %). A slow pulse animation is optional; respect reduced motion.
- At the bottom: **Dismiss**, a 76 dp pill filled `OnAccent` with `Accent` text. Below it, **Snooze N min**, a 76 dp pill with a 2 dp `OnAccent` outline. The caption "Auto-snoozes after N min of ringing" is at 80 % alpha.
- In fallback mode there is no Snooze button; keep the current behaviour.

### Widgets (`Widgets.dc.html`, Glance)
- **4×2**: `#0F1216` background, radius 28. Top row: an `Accent` dot with "Next wake-up", and the relative time on the right. Then the time at about 56 sp/300 and "Tomorrow · Work". A "Skip" pill on the right runs the existing `SkipNextAction`.
- **2×2**: `Accent` background with `OnAccent` text, the app mark top-left, the time at 44 sp, and "Tue · Work".
- **Unhealthy state**: the same 2×2 layout on dark with a `DangerLine` border, "⚠ 1 issue" in `DangerText` (use the icon, not the glyph), and "Tap to fix".
- **Empty**: "No alarms set" with a round + button that opens the one-off editor. (Not "in the next 14 days": the widget shows the armed alarm, which can be further away.)
- **4×1 with an alarm**: one row with the time, "Tomorrow · Work" and Skip. A failing check on 4×2 swaps "Next wake-up" for "N issues" and adds the `DangerLine` frame.
- Glance can't load custom fonts, so use the system sans with `FontWeight.Light` for the big time. Use `SizeMode.Responsive` to pick the layout.

---

## 4. Implementation notes

Decisions made while building the redesign, which the mockups do not show:

- **Header position.** The mockups have no status bar, so headers are placed at their absolute offset from the top of the screen (52 dp for tabs, 44 dp for the editor top bar), or 8 dp below the status bar when it is taller.
- **Line heights.** Compose does not shrink a single line below its font metrics, so the display times use `Modifier.lineBox(style, em)` (in `Type.kt`) to reproduce CSS `line-height: 0.9` / `1`.
- **Timeline.** One-off rows keep both Edit and Skip (Skip deletes it with Undo). Skipped rows read "skipped"; alarms replaced by a one-off are not in the timeline at all.
- **Hero card.** "Skip this one" appears when the shown occurrence is on the timeline (not for a snooze or an overdue alarm). The day comes from `TimeText.dayAndTime`, so the policy card reads "Active · next Tomorrow 06:30".
- **Policy editor.** Every rule card is shown expanded (the collapsed style is not built). "Active from/until" opens the date picker on tap and has a × to clear it; pause dates are underlined to show they are tappable.
- **Health.** The old intro paragraph is gone; the summary line replaces it. Passing manual checks read "HyperOS" (Xiaomi checks) or "Confirmed" (others), and tapping one offers "Mark as not done".
- **Ringing.** The circles share a centre at 560/844 of the screen height and pulse (2.4 s, ±6 %) unless the system animator scale is 0.
- **Widgets.** The time uses the system `sans-serif-light`; a failing-checks tap opens Health.

## 5. Suggested implementation order (one PR each)
1. Theme: tokens, fonts and `ReveilleTheme`. Switch off dynamic colour.
2. Navigation: bottom bar, tab routes, and the one-off editor as a bottom sheet.
3. Today screen.
4. Policies list and editor.
5. One-off sheet.
6. Health and Settings.
7. Ringing screen.
8. Widgets.

For every PR, keep the unit tests green and update the Maestro flows (`.maestro/*.yaml`) for any button text or navigation changes. For example, the "Policies" and "Settings" top-bar buttons become bottom-nav items, and Save moves to the bottom bar.
