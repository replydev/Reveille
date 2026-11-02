package com.replydev.reveille.update

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.replydev.reveille.BuildConfig

/**
 * The update feature's wiring. Built lazily by `AppContainer` and only from the UI or [UpdateWorker], both of which
 * run after the first unlock: its DataStore lives in credential-encrypted storage.
 */
class Updates(context: Context) {
    private val app = context.applicationContext

    /** Release builds update themselves; debug builds are signed with another key, so installing would always fail. */
    val selfUpdate: Boolean = !BuildConfig.DEBUG

    val installedVersionCode: Int = BuildConfig.VERSION_CODE
    val installedVersionName: String = BuildConfig.VERSION_NAME

    val store: UpdateStore by lazy {
        UpdateStore(
            PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }) {
                app.preferencesDataStoreFile("updates")
            },
        )
    }

    val source: ReleaseSource by lazy { GitHubReleaseSource(UrlConnectionHttp(), "Reveille/$installedVersionName") }

    val notifier: UpdateNotifier by lazy { UpdateNotifier(app) }

    val checker: UpdateChecker by lazy { UpdateChecker(store, source, installedVersionCode) { notifier.show(it) } }

    val work: UpdateWork by lazy { UpdateWork(store, checker, installedVersionCode) { notifier.show(it) } }

    /** Re-enqueues or cancels the daily check to match the setting. Debug builds never check automatically. */
    suspend fun syncSchedule() {
        UpdateWorker.sync(app, autoCheck = selfUpdate && store.current().autoCheck)
    }
}
