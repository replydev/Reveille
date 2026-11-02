package com.replydev.reveille

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.replydev.reveille.alarm.AlarmFiring
import com.replydev.reveille.alarm.AlarmScheduler
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.data.ReveilleDatabase
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.data.SettingsStore
import com.replydev.reveille.reliability.ReliabilityChecker
import com.replydev.reveille.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency wiring. Everything that alarm delivery touches lives in device-protected storage. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val deviceStorage = app.createDeviceProtectedStorageContext()

    /** App-wide scope for fire-and-forget work that must outlive any screen (widget refreshes, app-start re-arm). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: ReveilleDatabase by lazy { ReveilleDatabase.create(app) }

    private val preferences: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            // A corrupt file must not stop alarms from being armed: start over with defaults rather than crash.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { deviceStorage.preferencesDataStoreFile("reveille") }
    }

    val schedulerState: SchedulerState by lazy { SchedulerState(preferences) }
    val settings: SettingsStore by lazy { SettingsStore(preferences) }
    val notifier: Notifier by lazy { Notifier(app) }
    val reliability: ReliabilityChecker by lazy { ReliabilityChecker(app, settings) }

    val repository: ScheduleRepository by lazy {
        ScheduleRepository(database.scheduleDao()) { scheduler.rearm("schedule changed") }
    }

    val scheduler: AlarmScheduler by lazy {
        AlarmScheduler(app, repository, database.fireLogDao(), schedulerState, notifier, onArmedChanged = {
            // Fire and forget: a re-arm must never wait on Glance.
            appScope.launch { WidgetUpdater.update(app) }
        })
    }

    val firing: AlarmFiring by lazy {
        AlarmFiring(
            scheduler,
            schedulerState,
            settings,
            notifier,
            startService = { armed, backup, guard -> RingingService.tryStart(app, armed, backup, guard) },
            ringingNow = { RingingService.ringing.value?.armed },
            dismissRinging = { RingingService.send(app, RingingService.ACTION_DISMISS) },
        )
    }
}
