package com.replydev.reveille.update

import java.time.Instant

/** A published GitHub release whose tag and assets match what `release.yml` produces. */
data class Release(
    val tag: String,
    val versionCode: Int,
    val publishedAt: Instant,
    /** The raw Markdown body; [ReleaseNotes.parse] turns it into what the sheet shows. */
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String,
    val htmlUrl: String,
) {
    val versionName: String get() = tag.removePrefix("v")
}

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) {
    /** The formula `release.yml` passes to Gradle, so it compares directly with `BuildConfig.VERSION_CODE`. */
    val versionCode: Int get() = major * 1_000_000 + minor * 1_000 + patch

    val name: String get() = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("""^v(\d{1,4})\.(\d{1,3})\.(\d{1,3})$""")

        /** `vX.Y.Z`, or null for anything else (including components that would collide or overflow the code). */
        fun parseTag(tag: String): ReleaseVersion? {
            val (major, minor, patch) = TAG.matchEntire(tag)?.destructured ?: return null
            val code = major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()
            if (code > Int.MAX_VALUE) return null
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** The `.sha256` asset: `sha256sum` output, `<64 hex>  reveille-vX.Y.Z.apk`. */
object Sha256File {
    private val HEX = Regex("^[0-9a-fA-F]{64}$")

    fun parse(text: String): String? {
        val first = text.trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: return null
        return first.takeIf { HEX.matches(it) }?.lowercase()
    }
}
