package com.replydev.reveille.alarm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.ui.theme.ReveilleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

// Legacy graphics: native graphics would load Robolectric's native runtime a second time (for fonts) in a JVM
// where the SQLite tests already loaded it, and that breaks every later test in the fork.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 33, 35], qualifiers = "w390dp-h844dp")
class RingingContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/Rome")
    private val now = LocalDateTime.of(2026, 11, 3, 7, 55).atZone(zone)
    private val state = RingState(
        now = now,
        title = "Work",
        policyId = 1,
        policy = PolicyRingInfo("Work", setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), now.plusDays(1).toInstant()),
        backupOf = now.toInstant(),
        snoozeMinutes = 9,
        autoSnoozeMinutes = 5,
        ringingFor = Duration.ofSeconds(42),
    )
    private var dismissed = 0
    private var snoozed = 0

    private fun render(theme: RingTheme, fallback: Boolean = false, ringState: RingState = state) {
        val actions = RingActions(onDismiss = { dismissed++ }, onSnooze = if (fallback) null else ({ snoozed++ }))
        compose.setContent { ReveilleTheme { RingingContent(theme, ringState, actions, motion = false) } }
    }

    /** Dismiss and Snooze are both there and both reach their action. */
    private fun assertBothActions(theme: RingTheme, snoozeText: String) {
        render(theme)
        compose.onNodeWithTag(ringThemeTag(theme)).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        compose.onNodeWithText(snoozeText, substring = true).assertIsDisplayed().performClick()
        assertEquals(1, dismissed)
        assertEquals(1, snoozed)
    }

    private fun assertFallbackHasNoSnooze(theme: RingTheme) {
        render(theme, fallback = true)
        compose.onNodeWithTag(ringThemeTag(theme)).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Snooze", substring = true).assertDoesNotExist()
    }

    @Test fun dawnRingsWithBothActions() = assertBothActions(RingTheme.DAWN, "Snooze 9 min")

    @Test fun midnightRingsWithBothActions() = assertBothActions(RingTheme.MIDNIGHT, "Snooze 9 min")

    @Test fun policyColourRingsWithBothActions() = assertBothActions(RingTheme.POLICY_COLOUR, "Snooze")

    @Test fun horizonRingsWithBothActions() = assertBothActions(RingTheme.HORIZON, "Snooze 9 min")

    @Test fun paperRingsWithBothActions() = assertBothActions(RingTheme.PAPER, "Snooze")

    @Test fun dawnFallbackHasNoSnooze() = assertFallbackHasNoSnooze(RingTheme.DAWN)

    @Test fun midnightFallbackHasNoSnooze() = assertFallbackHasNoSnooze(RingTheme.MIDNIGHT)

    @Test fun policyColourFallbackHasNoSnooze() = assertFallbackHasNoSnooze(RingTheme.POLICY_COLOUR)

    @Test fun horizonFallbackHasNoSnooze() = assertFallbackHasNoSnooze(RingTheme.HORIZON)

    @Test fun paperFallbackHasNoSnooze() = assertFallbackHasNoSnooze(RingTheme.PAPER)

    @Test
    fun midnightShowsTheBackupTimeAndDimFootnote() {
        render(RingTheme.MIDNIGHT)
        compose.onNodeWithText("Backup rings at", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Screen stays dim · auto-snoozes after 5 min").assertIsDisplayed()
    }

    @Test
    fun policyColourShowsThePolicyAndItsNextAlarm() {
        render(RingTheme.POLICY_COLOUR)
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Next: Tomorrow", substring = true).assertIsDisplayed()
        compose.onNodeWithText("9 min").assertIsDisplayed()
    }

    @Test
    fun policyColourWithoutAPolicyHidesNextAndShowsTheLabel() {
        render(RingTheme.POLICY_COLOUR, ringState = state.copy(title = "Dentist", policyId = null, policy = null))
        compose.onNodeWithText("Dentist").assertIsDisplayed()
        compose.onNodeWithText("Next:", substring = true).assertDoesNotExist()
    }

    @Test
    fun paperShowsHowLongItHasRung() {
        render(RingTheme.PAPER)
        compose.onNodeWithText("0:42").assertIsDisplayed()
        compose.onNodeWithText("+9 min").assertIsDisplayed()
    }
}
