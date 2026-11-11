package com.replydev.reveille.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.replydev.reveille.alarm.SoundRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Robolectric only for android.util.Log; the probe is faked, so no MediaPlayer runs. */
@RunWith(AndroidJUnit4::class)
class SoundLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }) }
    private val dir by lazy { File(tmp.root, "sounds") }
    private fun library(probe: (File) -> Boolean = { true }, maxBytes: Long = 1_000) = SoundLibrary(dir, settings, probe, maxBytes)
    private fun bytes(n: Int) = { ByteArrayInputStream(ByteArray(n)) as InputStream }

    @Test
    fun importCopiesTheFileAndListsItWithoutExtension() = runBlocking {
        val result = library().import("Morning birds.mp3", declaredSize = 10, open = bytes(10))
        val sound = (result as ImportResult.Imported).sound
        assertEquals("Morning birds", sound.name)
        assertEquals(10L, library().file(sound.id).length())
        assertEquals(listOf(sound), settings.current().importedSounds)
        assertEquals(listOf(sound.id), dir.list()!!.toList())
    }

    @Test
    fun tooLargeIsRejectedByDeclaredOrActualSize() = runBlocking {
        assertEquals(ImportResult.TooLarge, library().import("a.mp3", declaredSize = 2_000, open = bytes(10)))
        assertEquals(ImportResult.TooLarge, library().import("a.mp3", declaredSize = null, open = bytes(2_000)))
        assertTrue(dir.list().orEmpty().isEmpty())
        assertTrue(settings.current().importedSounds.isEmpty())
    }

    @Test
    fun notAudioIsRejected() = runBlocking {
        assertEquals(ImportResult.NotAudio, library(probe = { false }).import("notes.txt", 10, bytes(10)))
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun unreadableIsRejected() = runBlocking {
        assertEquals(ImportResult.Unreadable, library().import("a.mp3", 10) { null })
        assertEquals(ImportResult.Unreadable, library().import("a.mp3", 10) { throw IOException("gone") })
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun importCancelledAfterTheWriteCommittedKeepsTheFile() = runBlocking {
        val real = PreferenceDataStoreFactory.create { File(tmp.root, "c.preferences_pb") }
        lateinit var job: Job
        // Commits, then the import is cancelled before the write returns (DataStore resuming its caller).
        val cancelling = object : DataStore<Preferences> {
            override val data = real.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                real.updateData(transform).also { job.cancel(); yield() }
        }
        val store = SettingsStore(cancelling)
        job = launch { SoundLibrary(dir, store, { true }, 1_000).import("a.mp3", 10, bytes(10)) }
        job.join()
        val listed = store.current().importedSounds
        assertEquals(1, listed.size)
        assertTrue(File(dir, listed.single().id).exists())
    }

    @Test
    fun deleteResetsUsesBeforeRemovingTheFile() = runBlocking {
        val sound = (library().import("a.mp3", 10, bytes(10)) as ImportResult.Imported).sound
        settings.update { it.copy(sound = SoundRef.Imported(sound.id)) }
        library().delete(sound.id)
        assertEquals(SoundRef.SystemDefault, settings.current().sound)
        assertTrue(settings.current().importedSounds.isEmpty())
        assertFalse(library().file(sound.id).exists())
    }

    @Test
    fun sweepRemovesUnlistedFilesAndTempFiles() = runBlocking {
        val kept = (library().import("a.mp3", 10, bytes(10)) as ImportResult.Imported).sound
        File(dir, "orphan").writeText("x")
        File(dir, "half.tmp").writeText("x")
        library().sweepOrphans()
        assertEquals(listOf(kept.id), dir.list()!!.toList())
    }
}
