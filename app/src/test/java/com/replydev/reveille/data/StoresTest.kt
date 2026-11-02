package com.replydev.reveille.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.replydev.reveille.engine.Armed
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
}
