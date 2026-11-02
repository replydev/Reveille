package com.replydev.reveille.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SchedulerState(private val store: DataStore<Preferences>) {
    val armedFlow: Flow<Armed?> = store.data.map { prefs -> prefs[ARMED]?.let(Armed::decode) }

    suspend fun armed(): Armed? = armedFlow.first()

    suspend fun setArmed(value: Armed?) = write(ARMED, value)

    suspend fun snooze(): Armed? = store.data.first()[SNOOZE]?.let(Armed::decode)

    suspend fun setSnooze(value: Armed?) = write(SNOOZE, value)

    suspend fun autoSnoozeCount(): Int = store.data.first()[AUTO_SNOOZES] ?: 0

    suspend fun setAutoSnoozeCount(value: Int) {
        store.edit { it[AUTO_SNOOZES] = value }
    }

    private suspend fun write(key: Preferences.Key<String>, value: Armed?) {
        store.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = value.encode() }
    }

    private companion object {
        val ARMED = stringPreferencesKey("armed")
        val SNOOZE = stringPreferencesKey("snooze")
        val AUTO_SNOOZES = intPreferencesKey("auto_snoozes")
    }
}
