package com.replydev.reveille.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePrefsTest {
    private val v030 = TestReleases.release("v0.3.0")
    private val installed = 2_000 // v0.2.0

    @Test
    fun onlyAHigherVersionIsAvailable() {
        assertEquals(v030, UpdatePrefs(latest = v030).available(installed))
        assertNull(UpdatePrefs(latest = v030).available(3_000))
        assertNull(UpdatePrefs(latest = v030).available(4_000))
        assertNull(UpdatePrefs(latest = null).available(installed))
    }

    @Test
    fun skippingHidesTheNudgeButNotTheSettingsRow() {
        val prefs = UpdatePrefs(latest = v030, skippedVersionCode = 3_000)
        assertEquals(v030, prefs.available(installed))
        assertNull(prefs.nudge(installed))
        assertFalse(prefs.shouldNotify(installed))
    }

    @Test
    fun dismissingHidesTheNudgeButStillAllowsTheNotification() {
        val prefs = UpdatePrefs(latest = v030, dismissedVersionCode = 3_000)
        assertNull(prefs.nudge(installed))
        assertTrue(prefs.shouldNotify(installed))
    }

    @Test
    fun aNewerReleaseThanTheSkippedOneNudgesAgain() {
        val v040 = TestReleases.release("v0.4.0")
        val prefs = UpdatePrefs(latest = v040, skippedVersionCode = 3_000, dismissedVersionCode = 3_000)
        assertEquals(v040, prefs.nudge(installed))
        assertTrue(prefs.shouldNotify(installed))
    }

    @Test
    fun notifiesOncePerVersionAndOnlyWhenNotifyIsOn() {
        assertTrue(UpdatePrefs(latest = v030).shouldNotify(installed))
        assertFalse(UpdatePrefs(latest = v030, notifiedVersionCode = 3_000).shouldNotify(installed))
        assertFalse(UpdatePrefs(latest = v030, notify = false).shouldNotify(installed))
        assertFalse(UpdatePrefs(latest = v030).shouldNotify(3_000))
    }
}
