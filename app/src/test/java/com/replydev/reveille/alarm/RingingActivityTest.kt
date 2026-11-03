package com.replydev.reveille.alarm

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.AppContainer
import com.replydev.reveille.ReveilleApp
import com.replydev.reveille.data.SettingsStore
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.testing.UnreadableDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

// Legacy graphics: native graphics would load Robolectric's native runtime a second time (for fonts) in a JVM
// where the SQLite tests already loaded it, and that breaks every later test in the fork.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 33, 35])
class RingingActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<ReveilleApp>()
    private lateinit var original: AppContainer
    private val armed = Armed("P:1:10:2026-11-03", Instant.parse("2026-11-03T06:55:00Z"), "Work", listOf("P:1:10:2026-11-03"))

    @Before
    fun setUp() {
        original = app.container
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun tearDown() {
        app.container = original
    }

    private fun launchFallback(): ActivityScenario<RingingActivity> =
        ActivityScenario.launch(RingingActivity.fallbackIntent(app, armed))

    private fun fallbackRendersWithoutSnooze(theme: RingTheme) {
        runBlocking { app.container.settings.update { it.copy(ringTheme = theme) } }
        launchFallback().use {
            compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(ringThemeTag(theme)) }
            compose.onNodeWithText("Dismiss").assertIsDisplayed()
            compose.onNodeWithText("Snooze", substring = true).assertDoesNotExist()
        }
    }

    @Test fun fallbackInDawn() = fallbackRendersWithoutSnooze(RingTheme.DAWN)

    @Test fun fallbackInMidnight() = fallbackRendersWithoutSnooze(RingTheme.MIDNIGHT)

    @Test fun fallbackInPolicyColour() = fallbackRendersWithoutSnooze(RingTheme.POLICY_COLOUR)

    @Test fun fallbackInHorizon() = fallbackRendersWithoutSnooze(RingTheme.HORIZON)

    @Test fun fallbackInPaper() = fallbackRendersWithoutSnooze(RingTheme.PAPER)

    @Test
    fun unreadableSettingsRenderDawn() {
        app.container = object : AppContainer(app as Context) {
            override val settings = SettingsStore(UnreadableDataStore())
        }
        launchFallback().use {
            compose.onNodeWithTag(ringThemeTag(RingTheme.DAWN)).assertIsDisplayed()
            compose.onNodeWithText("Dismiss").assertIsDisplayed()
        }
    }

    @Test
    fun midnightDimsOnlyItsWindow() {
        runBlocking { app.container.settings.update { it.copy(ringTheme = RingTheme.MIDNIGHT) } }
        launchFallback().use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodesWithTagExists(ringThemeTag(RingTheme.MIDNIGHT)) }
            scenario.onActivity { org.junit.Assert.assertEquals(0.15f, it.window.attributes.screenBrightness, 0f) }
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
