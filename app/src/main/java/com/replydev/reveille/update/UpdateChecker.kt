package com.replydev.reveille.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

sealed interface CheckOutcome {
    data object UpToDate : CheckOutcome

    data class Available(val release: Release) : CheckOutcome

    data class Failed(val error: FetchError) : CheckOutcome

    /** A background check while "Check automatically" is off. */
    data object Disabled : CheckOutcome
}

/**
 * Fetches the latest release, stores it, and posts the update notification once per version. Only successful checks
 * move "Last checked". A manual check never notifies (the user is looking at the app), but counts the version as seen.
 */
class UpdateChecker(
    private val store: UpdateStore,
    private val source: ReleaseSource,
    private val installedVersionCode: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val notify: suspend (Release) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun check(manual: Boolean): CheckOutcome = mutex.withLock {
        val before = store.current()
        if (!manual && !before.autoCheck) return CheckOutcome.Disabled
        val now = clock.instant()
        when (val result = source.latest(before.etag)) {
            is FetchError -> return CheckOutcome.Failed(result)
            is FetchResult.Found -> store.recordCheck(now, result.etag, result.release)
            is FetchResult.NoRelease -> store.recordCheck(now, result.etag, null)
            FetchResult.NotModified -> store.recordNotModified(now)
        }
        val after = store.current()
        val release = after.available(installedVersionCode) ?: return CheckOutcome.UpToDate
        if (after.shouldNotify(installedVersionCode)) {
            if (!manual) notify(release)
            store.markNotified(release.versionCode)
        }
        CheckOutcome.Available(release)
    }
}
