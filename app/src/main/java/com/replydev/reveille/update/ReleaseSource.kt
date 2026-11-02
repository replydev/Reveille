package com.replydev.reveille.update

import java.time.Instant

/** Where releases come from: GitHub in the app, [FakeReleaseSource] in tests and the debug Maestro flow. */
interface ReleaseSource {
    /** Never throws (except cancellation): every failure is a [FetchError]. */
    suspend fun latest(etag: String?): FetchResult
}

sealed interface FetchResult {
    data class Found(val release: Release, val etag: String?) : FetchResult

    /** No release, or the latest one isn't a `vX.Y.Z` tag we can install. */
    data class NoRelease(val etag: String?) : FetchResult

    /** `304`: the release stored with this ETag is still the latest. */
    data object NotModified : FetchResult
}

sealed interface FetchError : FetchResult {
    data object Offline : FetchError

    data class RateLimited(val until: Instant?) : FetchError

    data class Http(val code: Int) : FetchError

    data object Malformed : FetchError
}
