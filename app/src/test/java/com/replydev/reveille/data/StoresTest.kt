package com.replydev.reveille.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.replydev.reveille.alarm.RingTheme
import com.replydev.reveille.engine.Armed
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class StoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") }

    @Test
    fun schedulerStateStoresArmedAndSnooze() = runBlocking {
        val state = SchedulerState(store())
        assertNull(state.armed())
        val armed = Armed("O:1", Instant.parse("2026-11-05T08:30:00Z"), "Dentist", listOf("O:1"))
        state.setArmed(armed)
        state.setSnooze(Armed.snooze(Instant.parse("2026-11-05T08:39:00Z"), "Dentist"))
        state.setAutoSnoozeCount(2)
        assertEquals(armed, state.armed())
        assertEquals("Dentist", state.snooze()?.label)
        assertEquals(2, state.autoSnoozeCount())
        state.setArmed(null)
        state.setSnooze(null)
        assertNull(state.armed())
        assertNull(state.snooze())
    }

    @Test
    fun settingsHaveDefaultsAndUpdate() = runBlocking {
        val settings = SettingsStore(store())
        assertEquals(RingSettings(), settings.current())
        settings.update { it.copy(snoozeMinutes = 5, vibrate = false, ringtoneUri = "content://x") }
        assertEquals(RingSettings(ringtoneUri = "content://x", vibrate = false, snoozeMinutes = 5), settings.current())
        settings.update { it.copy(ringtoneUri = null) }
        assertNull(settings.current().ringtoneUri)
    }

    @Test
    fun manualConfirmationsToggle() = runBlocking {
        val settings = SettingsStore(store())
        settings.setConfirmed("miui_autostart", true)
        settings.setConfirmed("miui_recents", true)
        settings.setConfirmed("miui_recents", false)
        assertEquals(setOf("miui_autostart"), settings.confirmedChecks.first())
    }

    @Test
    fun ringThemeDefaultsToDawnAndRoundTrips() = runBlocking {
        val settings = SettingsStore(store())
        assertEquals(RingTheme.DAWN, settings.current().ringTheme)
        assertEquals(emptyMap<Long, RingTheme>(), settings.current().policyRingThemes)
        settings.update { it.copy(ringTheme = RingTheme.MIDNIGHT) }
        assertEquals(RingTheme.MIDNIGHT, settings.current().ringTheme)
    }

    @Test
    fun policyOverridesAreSetClearedAndPrunedOfDeletedPolicies() = runBlocking {
        val settings = SettingsStore(store())
        settings.setPolicyRingTheme(1, RingTheme.PAPER, livePolicyIds = setOf(1, 2, 3))
        settings.setPolicyRingTheme(3, RingTheme.HORIZON, livePolicyIds = setOf(1, 2, 3))
        assertEquals(mapOf(1L to RingTheme.PAPER, 3L to RingTheme.HORIZON), settings.current().policyRingThemes)
        // Policy 3 was deleted meanwhile: the next write drops it.
        settings.setPolicyRingTheme(2, RingTheme.MIDNIGHT, livePolicyIds = setOf(1, 2))
        assertEquals(mapOf(1L to RingTheme.PAPER, 2L to RingTheme.MIDNIGHT), settings.current().policyRingThemes)
        // null means "Default".
        settings.setPolicyRingTheme(1, null, livePolicyIds = setOf(1, 2))
        assertEquals(mapOf(2L to RingTheme.MIDNIGHT), settings.current().policyRingThemes)
    }

    @Test
    fun unknownStoredThemesMapToDawnAndMalformedOverridesAreIgnored() = runBlocking {
        val prefs = store()
        prefs.edit {
            it[stringPreferencesKey("ring_theme")] = "SEPIA"
            it[stringSetPreferencesKey("policy_ring_themes")] = setOf("1=PAPER", "2=SEPIA", "x=MIDNIGHT", "garbage")
        }
        val settings = SettingsStore(prefs)
        assertEquals(RingTheme.DAWN, settings.current().ringTheme)
        assertEquals(mapOf(1L to RingTheme.PAPER, 2L to RingTheme.DAWN), settings.current().policyRingThemes)
    }

    @Test
    fun lastReadRemembersTheMostRecentSettings() = runBlocking {
        val settings = SettingsStore(store())
        assertNull(settings.lastRead)
        settings.update { it.copy(ringTheme = RingTheme.PAPER) }
        settings.current()
        assertEquals(RingTheme.PAPER, settings.lastRead?.ringTheme)
    }
}
