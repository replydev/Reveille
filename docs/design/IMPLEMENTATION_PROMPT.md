Implement the Reveille redesign described in `docs/design/README.md`. The pixel-exact mockups are `docs/design/mockups/*.dc.html` (HTML with inline styles; 1 CSS px = 1 dp/sp; sample data sits in each file's `renderVals()` block). Read `AGENTS.md`, the full `docs/design/README.md` and every mockup before writing any code.

## Ground rules

- This is a **visual and navigation redesign only**. Don't change behaviour, validation, data, the engine or anything on the alarm path. The one rule in AGENTS.md still applies. The real copy comes from `TimeText`, `policyStatus`, `upcomingWarning`, `ReliabilityChecker` and the existing strings; the mockups' sample data is only illustrative.
- `RingingActivity` must keep working in Direct Boot and before first unlock. It must not depend on anything that needs credential-encrypted storage or the network. That is why fonts are bundled, not downloaded.
- minSdk 31: no APIs above 31 without an SDK check, and lint must report 0 errors.
- **Icons**: don't add the Material icons artifact. Create the few icons you need as hand-written vector drawables in `app/src/main/res/drawable/` (prefix `ic_`, 24 dp, 2 dp round stroke, matching the mockups' inline SVG paths). Every icon-only button has a `contentDescription` (for example "Back", "Delete policy", "Close", "Remove alarm time"). Maestro selects by that text.
- **Fonts**: bundle Geist (Light 300, Regular 400, Medium 500, SemiBold 600) and Geist Mono (Regular 400, Medium 500) as TTFs in `app/src/main/res/font/`, from the official OFL release (github.com/vercel/geist-font). Add the OFL licence file to the repo. If you can't download them, stop and tell me rather than substituting another font. Glance widgets use the system font.
- Prefer small reusable components in `ui/components/` (for example `ReveilleCard`, `PillButton`, `PillStepper`, `DayToggleRow`, `MiniDayCells`, `Overline`, `PolicyDot`, `StatusPill`, `ReveilleSwitch` colours, `ReveilleNavBar`) over per-screen copies.
- TDD where there is logic. Pure helpers such as the policy colour mapping, the "backup HH:MM" text, the health summary and the "Set for …" button text get unit tests first.

## Step 0: update AGENTS.md (do this first, in its own commit)

Edit `AGENTS.md` as follows. Keep everything else unchanged.

1. In **Layout**, under `ui/`, add this bullet:
   ```
   - `ui/theme/`: `Color.kt` (fixed dark palette + `ReveilleColors` CompositionLocal), `Type.kt` (Geist / Geist Mono, tabular numerals), `Theme.kt`, `Motion.kt`. No dynamic colour.
   ```
2. Add a new section after **Layout**:
   ```
   ## Design

   The UI follows `docs/design/README.md` (tokens, components, per-screen spec) and the mockups in `docs/design/mockups/*.dc.html` (1 CSS px = 1 dp/sp; sample data is in each file's `renderVals()`). Canvas: https://claude.ai/artifact/9ZYXbAyXxZyZL3u5RVp2pu.

   - Use the tokens in `ui/theme/` and the shared components in `ui/components/`. Never hard-code a hex colour or font size in a screen.
   - Dark-only, fixed palette; `Accent` (#FFB547) is the single accent. Policy colours come from `policyColor(id)`, not the database.
   - Navigation: a bottom bar with Today / Policies / Health / Settings. The editors are pushed screens (policy) or a bottom sheet (one-off) without the bar.
   - Cards are flat (1 dp outline, no elevation). Touch targets are ≥ 44 dp. Text meets 4.5:1 contrast; `Faint` is never used for body text.
   - When a screen changes, update its mockup and the spec if the design itself changed, not just the code.
   ```
3. In **Conventions**, replace `- No Material icons artifact; buttons use text labels.` with:
   ```
   - No Material icons artifact: icons are hand-written vector drawables in `res/drawable/ic_*.xml`. Icon-only buttons always have a `contentDescription`, which is also what Maestro selects on.
   ```
4. In **UI testing (Maestro)**, replace the bullet that begins `Select elements by their visible text` with:
   ```
   - Select elements by their visible text or, for icon-only buttons, their `contentDescription`. Top-level screens are reached through the bottom bar ("Today", "Policies", "Health", "Settings"). When a label appears twice, anchor it with `below:`.
   ```

Commit: `docs: add design system guidance to AGENTS.md`.

## Steps 1–8: implementation

Do these in order, as one commit per step on a branch `feat/redesign`. Use conventional commits: `feat(ui): …` for steps that change what users see, and `build:` / `refactor(ui):` where that is more accurate. After **every** step, run:

```
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Then install and run `maestro test .maestro` on the `reveille` emulator. Update the flows in the same commit whenever labels or navigation change. Take a screenshot of each changed screen (`adb exec-out screencap -p`), compare it side by side with its mockup, and fix visible differences in spacing, size, colour and radius before moving on. Tell me about any deviation you chose to keep.

1. **Theme**: fonts, `Color.kt`, `Type.kt`, `ReveilleTheme` with a fixed dark scheme and `ReveilleColors`, `policyColor(id)` (plus tests), shared components, and window/status-bar colours (edge-to-edge on `Bg`). Also update `values/themes.xml` and `values-night/themes.xml` so the launch window is `#0B0D11` with no white flash.
2. **Navigation** (README §2): the `NavigationBar` with four tabs, the Health dot badge (move the failing-checks computation somewhere shared rather than duplicating it), tab switches without the shared-axis slide, editors without the bar, and the one-off editor as a `ModalBottomSheet` destination that keeps `ScreenSession`/`SavedStateHandle` semantics, including process death and the "this alarm no longer exists" path. Rewrite `navigation.yaml` for the bottom bar.
3. **Today** (`Main.dc.html`): header with the date and the reliability pill (replaces the banner card), the hero next-wake-up card covering every `NextUp` state including the 3 s grace logic, the 14-day timeline with day columns, and the FAB "One-off alarm". Remove "New policy" from Today.
4. **Policies list and policy editor** (`Policies.dc.html`, `PolicyEditor.dc.html`): policy cards with mini day cells; the editor's name and enabled tiles, date tiles, rule cards (at minimum the expanded style for every rule; expand/collapse is optional), dashed add buttons, pause rows, and a sticky bottom bar with "Next: …", warnings/errors and Save. Update `policy.yaml`.
5. **One-off sheet** (`OneOff.dc.html`): hour and minute boxes that open the existing time picker, the date tile strip (today + 7 days and a calendar tile), the label field, the "Replace the day" / "Ring in addition" segmented control, the impact list, the past-time error, and the "Set for …" button. Update `one_off_alarm.yaml`.
6. **Health and Settings** (`Health.dc.html`, `Settings.dc.html`): the summary line and segmented bar, failing checks first as danger cards, passing checks grouped, manual-check confirm and unconfirm, the test alarm card, the force-stop footnote, and the recent-alarms log; Settings in grouped cards with pill steppers and an About row with `BuildConfig.VERSION_NAME` (enable `buildConfig` if needed).
7. **Ringing** (`Ringing.dc.html`): full-bleed accent, the live clock, the title chip, the concentric circles (an optional slow pulse, disabled when animations are off), and the 76 dp Dismiss and Snooze pills. Keep fallback mode (no Snooze), the back-press block and the lock-screen flags exactly as they are.
8. **Widgets** (`Widgets.dc.html`): the Glance layouts with `SizeMode.Responsive` for 4×2, 2×2, unhealthy and empty. Keep `SkipNextAction`'s parameters and the rule that the widget only renders the persisted armed alarm. Update `reveille_widget_info.xml` sizes if needed.

## Done means

- All four Gradle tasks pass and lint reports 0 errors.
- All Maestro flows pass.
- Every screen visibly matches its mockup, and you have listed any intentional deviations.
- AGENTS.md is updated and the README is still accurate. If you changed a design decision, update `docs/design/README.md` too.
- There is a short summary per step of what changed and what still needs a real-phone check: Direct Boot ringing screen, full-screen intent and widget rendering on the launcher.

Don't push or open a PR unless I ask.
