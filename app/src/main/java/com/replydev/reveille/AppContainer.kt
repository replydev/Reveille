package com.replydev.reveille

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.replydev.reveille.alarm.AlarmScheduler
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.data.ReveilleDatabase
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.data.SettingsStore

/** Manual dependency wiring. Everything that alarm delivery touches lives in device-protected storage. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val deviceStorage = app.createDeviceProtectedStorageContext()

    val database: ReveilleDatabase by lazy { ReveilleDatabase.create(app) }

    private val preferences: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create { deviceStorage.preferencesDataStoreFile("reveille") }
    }

    val schedulerState: SchedulerState by lazy { SchedulerState(preferences) }
    val settings: SettingsStore by lazy { SettingsStore(preferences) }
    val notifier: Notifier by lazy { Notifier(app) }

    val repository: ScheduleRepository by lazy {
        ScheduleRepository(database.scheduleDao()) { scheduler.rearm("schedule changed") }
    }

    val scheduler: AlarmScheduler by lazy {
        AlarmScheduler(app, repository, database.fireLogDao(), schedulerState, notifier, onArmedChanged = {})
    }
}
