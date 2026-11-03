package com.replydev.reveille.update

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.replydev.reveille.BuildConfig
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.data.SchedulerState
import com.replydev.reveille.engine.ArmPlanner
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The update feature's wiring. Built lazily by `AppContainer` and only from the UI or [UpdateWorker], both of which
 * run after the first unlock: its DataStore lives in credential-encrypted storage. It reads the alarm state to guard
 * installs; nothing on the alarm side reads anything here.
 */
class Updates(context: Context, private val scope: CoroutineScope, private val schedulerState: SchedulerState) {
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

    private val source = SwitchableReleaseSource(GitHubReleaseSource(UrlConnectionHttp(), "Reveille/$installedVersionName"))

    /** Debug builds only: serve [FakeReleaseSource.SAMPLE] instead of GitHub (the Maestro flow runs offline). */
    fun useFakeSource() {
        if (BuildConfig.DEBUG) source.delegate = FakeReleaseSource()
    }

    val notifier: UpdateNotifier by lazy { UpdateNotifier(app) }

    val checker: UpdateChecker by lazy { UpdateChecker(store, source, installedVersionCode) { notifier.show(it) } }

    val work: UpdateWork by lazy { UpdateWork(store, checker, installedVersionCode) { notifier.show(it) } }

    private val installer: UpdateInstaller by lazy { UpdateInstaller(app) }

    val controller: UpdateController by lazy {
        val verifier = ApkVerifier(AndroidApkInspector(app))
        UpdateController(
            scope = scope,
            downloader = UpdateDownloader(File(app.cacheDir, "updates"), UrlConnectionHttp()),
            verify = verifier::verify,
            installer = object : ApkInstaller {
                override fun canRequestInstalls() = installer.canRequestInstalls()
                override fun install(apk: File) {
                    installer.install(apk)
                }
            },
            guard = { guard() },
        ).also { controller -> UpdateInstaller.listeners += controller::installResult }
    }

    fun permissionIntent() = installer.permissionIntent()

    /**
     * What the install guard sees now. The ring-guard slot counts as ringing only inside the late-ring window: a slot
     * that outlived its occurrence must not block updates for good.
     */
    suspend fun guard(now: Instant = Instant.now()): GuardResult {
        val claimed = schedulerState.guard()?.takeIf { Duration.between(it.at, now) <= ArmPlanner.LATE_RING_WINDOW }
        val ringing = RingingService.ringing.value?.armed ?: claimed
        return InstallGuard.check(schedulerState.armed(), schedulerState.snooze(), ringing, now)
    }

    /** "Remind me" in the alarm guard: remind 5 minutes after the alarm, once it has rung. */
    suspend fun remindAfter(alarmAt: Instant) {
        val at = reminderAt(alarmAt)
        store.setRemindAfter(at)
        UpdateWorker.scheduleRemind(app, at)
    }

    /** Re-enqueues or cancels the daily check to match the setting. Debug builds never check automatically. */
    suspend fun syncSchedule() {
        UpdateWorker.sync(app, autoCheck = selfUpdate && store.current().autoCheck)
    }
}

/** Lets debug builds swap GitHub for [FakeReleaseSource] at runtime. */
private class SwitchableReleaseSource(@Volatile var delegate: ReleaseSource) : ReleaseSource {
    override suspend fun latest(etag: String?): FetchResult = delegate.latest(etag)
}
