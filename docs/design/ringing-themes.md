# Ringing themes and theme picker: spec

The full-screen alarm gets **five themes**. The user picks a default theme, and can override it for each policy.

Design: the canvas row "Reveille — ringing themes" at https://claude.ai/artifact/9ZYXbAyXxZyZL3u5RVp2pu. Mockups: `docs/design/mockups/`.

| Theme | Mockup | One-line intent |
|---|---|---|
| **Dawn** (default) | `Ringing.dc.html` (existing) | Full-bleed amber, the current design |
| **Midnight** | `RingMidnight.dc.html` | Pure black with dim amber text, for dark rooms and early shifts |
| **Policy colour** | `RingPolicy.dc.html` | Background in the alarm's policy colour, so routines are told apart at a glance |
| **Horizon** | `RingHorizon.dc.html` | Flat sky bands with the Daybreak sun rising on the horizon |
| **Paper** | `RingPaper.dc.html` | Light, editorial, heavy type; shows how long it has rung |
| Picker | `RingPicker.dc.html` | Settings › Ringing theme |

`RingSlide.dc.html` (slide to dismiss) is **not** in scope. It lives only on the canvas.

---

## 1. Rules (alarm path)

1. **A theme changes only how the screen looks.** Sound, vibration, volume ramp, snooze and auto-snooze behave exactly the same in every theme. Midnight dims the *screen* and never the sound.
2. **Direct Boot.** `RingingActivity` must render any theme before first unlock. So:
   - the chosen theme and the per-policy overrides live in the existing **device-protected** `SettingsStore` DataStore;
   - all fonts are bundled. Add Geist **ExtraLight (200)** and **Bold (700)** to `res/font`, because Midnight, Policy colour and Horizon use 200 and Paper uses 700.
   - If the setting can't be read, fall back to **Dawn**. Never block or delay the screen on it.
3. **Fallback mode** (opened by the fallback notification) keeps working in every theme, with no Snooze button, as today.
4. **Every theme must keep both actions large:** Dismiss and Snooze are at least 64 dp tall and full width, or 112 dp tall in Policy colour's two-column layout.
   - Back stays blocked.
   - Text contrast is at least 4.5:1, or 3:1 for text of 24 sp and up. Check Policy colour's ink `#0B1A33` against every policy colour, and Midnight's `#8A7550` secondary text against `#000` (large or secondary text only).

## 2. Model and storage

- `enum class RingTheme { DAWN, MIDNIGHT, POLICY_COLOUR, HORIZON, PAPER }`, stored by name with **default DAWN**. An unknown name maps to DAWN.
- `SettingsStore` gains:
  - `ringTheme: RingTheme`;
  - `policyRingThemes: Map<Long, RingTheme>` (policyId → override; no entry means "Default").
  - These live in DataStore, so **no Room migration**. Ignore entries for deleted policies, and drop them on the next write.
- **Resolution** is a pure function `resolveTheme(armed: Armed, default, overrides): RingTheme` with unit tests:
  - a policy alarm (key `P:{policyId}:…`) uses its override if there is one, else the default;
  - a snooze keeps the theme of the alarm it snoozes;
  - one-offs, the test alarm and anything unknown use the default.
- **Policy colour without a policy** (a one-off, the test alarm, or a policy deleted meanwhile) renders with `Accent` `#FFB547` as the background and `OnAccent` ink.

## 3. Themes (match the mockups; 1 CSS px = 1 dp/sp)

All themes show the **live clock** (the current time, ticking every second, as today), the alarm label, and Dismiss / Snooze N min. N comes from the snooze setting, and "auto-snoozes after M min" uses `maxRingMinutes`.

### Midnight
- Background `#000000`. Clock in `Accent` at 132 sp/200. Date overline 13 sp/500 in `#8A7550`.
- Label: 18 sp/500 in `#E8D3AE` with the policy colour square. The second line reads "Backup rings at HH:MM" (primary + 2 min).
- Buttons are 80 dp pills:
  - Dismiss: `AccentContainer` `#2A2418` fill with `Accent` text;
  - Snooze: 1.5 dp `AccentLine` `#3A3020` outline with `#E8D3AE` text.
- Footnote: "Screen stays dim · auto-snoozes after M min".
- **Screen brightness**: set `window.attributes.screenBrightness = 0.15f` for this theme only. Restore it on dismiss or snooze; it only affects this window.

### Policy colour
- Background is the policy colour (`policyColor(id)`) and the ink is `#0B1A33`.
- **Top:** the policy name (44 sp/600) with a days pill on the right (`TimeText.days` for the rule), then the date (15 sp, 80 % ink).
- **Middle:** a 1.5 dp rule at 25 % ink, then the clock at 148 sp/200.
- **Below the clock:** "Next: {day HH:MM}", the next occurrence of the same policy. Hide it if there is none.
- **Buttons:** two columns, 112 dp tall, radius 28. Snooze on the left: 2 dp ink outline, label plus "N min". Dismiss on the right: ink fill with policy-colour text.

### Horizon
- Sky `#10142A`, with flat bands from 300 dp down to the horizon at 600 dp:

  | Colour | Height |
  |---|---|
  | `#1C1F45` | 80 dp |
  | `#352A5C` | 60 dp |
  | `#63365E` | 60 dp |
  | `#A84A5E` | 50 dp |
  | `#E57B4E` | 50 dp |

- **Sun:** an `Accent` disc 180 dp in diameter, centred on the horizon. The ground below the horizon is `Bg` `#0B0D11` with a 3 dp `Accent` top line, and it covers the sun's lower half.
- Clock and text are centred near the top. The clock is 120 sp/200 in `#F3F4F6`, the date overline in `#C9CDE8`, and the label in `#E4E6F5`.
- **Buttons on the ground:**
  - Dismiss: 72 dp, `Accent` fill with `OnAccent` text;
  - Snooze: 64 dp, outlined in `OutlineStrong`.
- **No gradients.** The bands are flat colour. An optional rise animation (the sun moving up 24 dp over 2 s) only plays when system animations are on.

### Paper
- Background `#F4F1EA`, ink `#14130F`.
- **Header:** "REVEILLE" (14/700, tracking +0.14em) on the left and the date on the right, with a 2 dp ink rule below.
- **Clock:** 156 sp/700, tracking -0.07em.
- **Two-cell info row** (hairline `#C9C3B5`): "ALARM" with the label, and "RINGING FOR" with the elapsed m:ss in mono.
- **Progress bar:** 4 dp on `#E2DDD1`, filling towards auto-snooze (elapsed ÷ `maxRingMinutes`) in the theme accent `#E8862A`.
- **Buttons:** 76 dp, radius 4, label left and detail right.
  - Dismiss: ink fill with a ✓ icon.
  - Snooze: 2 dp ink outline with "+N min" in mono.

### Dawn
- Unchanged: the current `RingingActivity` design.

## 4. Picker (`RingPicker.dc.html`)

- **Entry point:** a new row in Settings › Sound, placed after "Alarm sound": "Ringing theme", with the current theme name as its subline and a chevron. It opens the route `settings/ringing-theme`, a pushed screen without the bottom bar.
- **Top bar:** back and the title "Ringing theme".
- **Theme carousel:** a horizontal `LazyRow` with 16 dp side padding and 12 dp gaps.
  - Each item is a 150 × 325 dp thumbnail (radius 22) with the theme name (15/600) and a hint (12 sp `Muted`) below it.
  - The hints are: Dawn "Default · amber", Midnight "Dim, for dark rooms", Policy colour "Tells routines apart", Horizon "Sunrise in bands", Paper "Light and bold".
  - **Thumbnails are the real theme composable**, rendered at 390 × 844 with sample data (07:55, "Work") and scaled by 0.3846. They are not bitmaps.
  - The selected item has a 2 dp `Accent` ring with a 3 dp `Bg` gap and a 28 dp `Accent` check badge in the top right.
  - The group is accessible as a radio group. Tapping an item selects it and saves immediately.
- **Buttons** (two equal 48 dp pills):
  - **Preview** (outlined) opens `RingingActivity` in a new **preview mode** with sample data. It plays no sound, takes no wake lock, and doesn't touch the fire log or `RingingService`. Its Dismiss and Snooze just close it. Pass the theme as an intent extra.
  - **Test with sound** (`SurfaceHigh`) schedules the existing Health test alarm (`scheduleTestAlarm`, 2 minutes) and shows its snackbar result. The test alarm rings with the default theme.
- **"PER POLICY" section:** the overline, with "One-offs use the default" on the right in `Muted`.
  - A grouped card with one 56 dp row per policy: the colour square, the name, the current value ("Default" in `Muted`, or a theme name in `OnBg`), and a chevron.
  - Tapping a row opens a small bottom sheet with radio options: Default, then the five themes.
  - Hide the section when there are no policies.

## 5. Tests
- **Unit tests:**
  - `resolveTheme` covers policy alarm with and without an override, a snooze of a policy alarm, a one-off, the test alarm, a deleted policy, and an unknown key;
  - the `RingTheme` name round-trip, with unknown names mapping to DAWN;
  - Paper's progress maths;
  - Midnight's backup-time text.
- **Robolectric tests:**
  - `RingingActivity` renders each theme, with Dismiss and Snooze present, and Snooze absent in fallback mode;
  - when the settings read throws, the screen renders Dawn;
  - preview mode doesn't start `RingingService` or write the fire log.
- **Maestro:** a new flow `.maestro/ringing_theme.yaml`:
  1. Settings → Ringing theme.
  2. Select Midnight; the check moves.
  3. Back; the subline reads "Midnight".
  4. Per policy → set an override; the row value updates.
  5. Preview opens, then Dismiss closes it.
