package com.replydev.reveille.data

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

sealed interface ImportResult {
    data class Imported(val sound: ImportedSound) : ImportResult
    data object TooLarge : ImportResult
    data object NotAudio : ImportResult
    data object Unreadable : ImportResult
}

/**
 * Imported alarm sounds: private copies in device-protected [dir], so they ring before the first unlock and survive the
 * original being moved. The alarm path only calls [file]; everything else runs from the UI or at app start.
 */
class SoundLibrary(
    private val dir: File,
    private val settings: SettingsStore,
    private val probe: (File) -> Boolean = ::canPlay,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = Mutex()

    fun file(id: String): File = File(dir, id)

    suspend fun importUri(resolver: ContentResolver, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val (name, size) = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
            }
        }.getOrNull() ?: (null to null)
        import(name ?: uri.lastPathSegment ?: "Sound", size) { resolver.openInputStream(uri) }
    }

    suspend fun import(displayName: String, declaredSize: Long?, open: () -> InputStream?): ImportResult = withContext(Dispatchers.IO) {
        if (declaredSize != null && declaredSize > maxBytes) return@withContext ImportResult.TooLarge
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val tmp = File(dir, "$id.tmp")
            try {
                dir.mkdirs()
                val input = open() ?: return@withLock ImportResult.Unreadable
                val copied = input.use { source -> tmp.outputStream().use { copyLimited(source, it) } }
                if (copied == null) return@withLock ImportResult.TooLarge
                if (!probe(tmp)) return@withLock ImportResult.NotAudio
                val target = file(id)
                if (!tmp.renameTo(target)) return@withLock ImportResult.Unreadable
                val sound = ImportedSound(id, displayName.substringBeforeLast('.').ifBlank { displayName })
                // Not cancellable: once the entry is committed, a cancellation must not delete the file it lists.
                withContext(NonCancellable) {
                    try {
                        settings.addImportedSound(sound)
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                }
                ImportResult.Imported(sound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot import $displayName", e)
                ImportResult.Unreadable
            } finally {
                tmp.delete()
            }
        }
    }

    /** Points every use back to the default first; only then removes the file. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            settings.removeImportedSound(id)
            file(id).delete()
        }
    }

    /** Removes files no entry refers to (a crash mid-import or mid-delete). Never runs on the alarm path. */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        lock.withLock {
            val listed = settings.current().importedSounds.map { it.id }.toSet()
            dir.listFiles().orEmpty().filter { it.name !in listed }.forEach { it.delete() }
        }
    }

    /** Copies at most [maxBytes]; returns the byte count, or null if the source is larger. */
    private fun copyLimited(source: InputStream, sink: java.io.OutputStream): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) return total
            total += n
            if (total > maxBytes) return null
            sink.write(buffer, 0, n)
        }
    }

    companion object {
        private const val TAG = "SoundLibrary"
        const val MAX_BYTES = 20L * 1024 * 1024

        private fun canPlay(file: File): Boolean {
            val player = MediaPlayer()
            return try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                player.setDataSource(file.path)
                player.prepare()
                true // some formats (raw AAC/ADTS) report no duration yet play
            } catch (e: Exception) {
                false
            } finally {
                player.release()
            }
        }
    }
}
