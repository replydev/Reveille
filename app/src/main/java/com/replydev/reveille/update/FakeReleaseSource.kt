package com.replydev.reveille.update

import java.time.Instant

/** Canned releases for tests and the debug Maestro flow, which must not need the network. */
class FakeReleaseSource(@Volatile var next: FetchResult = FetchResult.Found(SAMPLE, null)) : ReleaseSource {
    @Volatile var calls = 0
        private set

    @Volatile var lastEtag: String? = null
        private set

    override suspend fun latest(etag: String?): FetchResult {
        calls++
        lastEtag = etag
        return next
    }

    companion object {
        /** The release in `UpdateSheet.dc.html`. */
        val SAMPLE = Release(
            tag = "v0.3.0",
            versionCode = 3_000,
            publishedAt = Instant.parse("2026-11-02T22:41:36Z"),
            notes = "## Features\n\n- feat(ui): replace the launcher icon with the Daybreak mark (#7)\n\n" +
                "## Fixes\n\n- fix(ui): use the shared axis transition for system Back instead of the default scale-out (#6)\n",
            apkUrl = "${GitHubReleaseSource.REPO_URL}/releases/download/v0.3.0/reveille-v0.3.0.apk",
            apkSize = 6_400_000,
            sha256Url = "${GitHubReleaseSource.REPO_URL}/releases/download/v0.3.0/reveille-v0.3.0.apk.sha256",
            htmlUrl = "${GitHubReleaseSource.REPO_URL}/releases/tag/v0.3.0",
        )
    }
}
