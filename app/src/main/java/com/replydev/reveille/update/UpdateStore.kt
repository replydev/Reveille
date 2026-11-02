package com.replydev.reveille.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.Instant

data class UpdatePrefs(
    val autoCheck: Boolean = true,
    val notify: Boolean = true,
    val lastCheckedAt: Instant? = null,
    val etag: String? = null,
    val latest: Release? = null,
    val skippedVersionCode: Int? = null,
    val dismissedVersionCode: Int? = null,
    /** Set by "Remind me" in the alarm guard: the earliest time to remind about the update again. */
    val remindAfter: Instant? = null,
    /** The version the update notification was last posted (or seen in the app) for: one notification per version. */
    val notifiedVersionCode: Int? = null,
) {
    /** The stored release if it is newer than the installed build. Settings shows it even when skipped. */
    fun available(installedVersionCode: Int): Release? = latest?.takeIf { it.versionCode > installedVersionCode }

    /** The release the Today card and the nav dot point at: available, not skipped and not dismissed. */
    fun nudge(installedVersionCode: Int): Release? = available(installedVersionCode)
        ?.takeIf { it.versionCode != skippedVersionCode && it.versionCode != dismissedVersionCode }

    fun shouldNotify(installedVersionCode: Int): Boolean {
        val release = available(installedVersionCode) ?: return false
        return notify && release.versionCode != skippedVersionCode && release.versionCode != notifiedVersionCode
    }
}

/**
 * Update state in its own DataStore file in credential-encrypted storage: it is not alarm data, and nothing on the
 * alarm path reads it.
 */
class UpdateStore(private val store: DataStore<Preferences>) {
    val prefs: Flow<UpdatePrefs> = store.data.map(::read)

    suspend fun current(): UpdatePrefs = prefs.first()

    suspend fun setAutoCheck(value: Boolean) = edit { it[AUTO_CHECK] = value }

    suspend fun setNotify(value: Boolean) = edit { it[NOTIFY] = value }

    suspend fun skip(versionCode: Int) = edit { it[SKIPPED] = versionCode }

    suspend fun dismiss(versionCode: Int) = edit { it[DISMISSED] = versionCode }

    suspend fun markNotified(versionCode: Int) = edit { it[NOTIFIED] = versionCode }

    suspend fun setRemindAfter(value: Instant?) = edit { it.put(REMIND_AFTER, value?.toEpochMilli()) }

    /** A successful check: the time, the ETag and the release it describes are written together. */
    suspend fun recordCheck(at: Instant, etag: String?, latest: Release?) = edit {
        it[LAST_CHECKED] = at.toEpochMilli()
        it.put(ETAG, etag)
        it.put(LATEST, latest?.let(::encode))
    }

    suspend fun recordNotModified(at: Instant) = edit { it[LAST_CHECKED] = at.toEpochMilli() }

    private fun read(prefs: Preferences): UpdatePrefs {
        val defaults = UpdatePrefs()
        val stored = prefs[LATEST]
        val latest = stored?.let(::decode)
        // An unreadable release must not be pinned by its ETag (every check would answer 304): refetch instead.
        val etag = prefs[ETAG].takeIf { stored == null || latest != null }
        return UpdatePrefs(
            autoCheck = prefs[AUTO_CHECK] ?: defaults.autoCheck,
            notify = prefs[NOTIFY] ?: defaults.notify,
            lastCheckedAt = prefs[LAST_CHECKED]?.let(Instant::ofEpochMilli),
            etag = etag,
            latest = latest,
            skippedVersionCode = prefs[SKIPPED],
            dismissedVersionCode = prefs[DISMISSED],
            remindAfter = prefs[REMIND_AFTER]?.let(Instant::ofEpochMilli),
            notifiedVersionCode = prefs[NOTIFIED],
        )
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun encode(release: Release): String = JSONObject()
        .put("tag", release.tag)
        .put("versionCode", release.versionCode)
        .put("publishedAt", release.publishedAt.toString())
        .put("notes", release.notes)
        .put("apkUrl", release.apkUrl)
        .put("apkSize", release.apkSize)
        .put("sha256Url", release.sha256Url)
        .put("htmlUrl", release.htmlUrl)
        .toString()

    private fun decode(text: String): Release? = try {
        val json = JSONObject(text)
        Release(
            tag = json.getString("tag"),
            versionCode = json.getInt("versionCode"),
            publishedAt = Instant.parse(json.getString("publishedAt")),
            notes = json.getString("notes"),
            apkUrl = json.getString("apkUrl"),
            apkSize = json.getLong("apkSize"),
            sha256Url = json.getString("sha256Url"),
            htmlUrl = json.getString("htmlUrl"),
        )
    } catch (_: JSONException) {
        null
    } catch (_: DateTimeException) {
        null
    }

    private companion object {
        val AUTO_CHECK = booleanPreferencesKey("auto_check")
        val NOTIFY = booleanPreferencesKey("notify")
        val LAST_CHECKED = longPreferencesKey("last_checked_at")
        val ETAG = stringPreferencesKey("etag")
        val LATEST = stringPreferencesKey("latest")
        val SKIPPED = intPreferencesKey("skipped_version_code")
        val DISMISSED = intPreferencesKey("dismissed_version_code")
        val NOTIFIED = intPreferencesKey("notified_version_code")
        val REMIND_AFTER = longPreferencesKey("remind_after")
    }
}
