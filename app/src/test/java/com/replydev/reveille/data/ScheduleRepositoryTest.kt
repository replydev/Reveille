package com.replydev.reveille.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryTest {
    private lateinit var db: ReveilleDatabase
    private lateinit var repository: ScheduleRepository
    private var changes = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReveilleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ScheduleRepository(db.scheduleDao()) { changes++ }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun skipOccurrenceSkipsPoliciesAndDeletesOneOffs() = runBlocking {
        val policyId = repository.savePolicy(
            PolicyEntity(name = "Work"),
            listOf(RuleEntity(policyId = 0, time = LocalTime.of(7, 55), days = setOf(MONDAY), anchorDate = LocalDate.parse("2026-11-02"))),
            emptyList(),
        )
        val oneOffId = repository.saveOneOff(OneOffEntity(date = LocalDate.parse("2026-11-02"), time = LocalTime.of(7, 55)))
        changes = 0

        repository.skipOccurrence(listOf("P:$policyId:1:2026-11-02", "O:$oneOffId", "garbage"))

        val snapshot = repository.snapshot()
        assertEquals(listOf(policyId to LocalDate.parse("2026-11-02")), snapshot.skips.map { it.policyId to it.date })
        assertTrue(snapshot.oneOffs.isEmpty())
        assertEquals(1, changes)
    }

    @Test
    fun cleanupDoesNotTriggerRearm() = runBlocking {
        repository.cleanup(LocalDate.parse("2026-11-02"))
        assertEquals(0, changes)
    }

    @Test
    fun skipOccurrenceIgnoresDeletedSources() = runBlocking {
        val policyId = repository.savePolicy(PolicyEntity(name = "Work"), emptyList(), emptyList())
        repository.deletePolicy(policyId)
        changes = 0

        repository.skipOccurrence(listOf("P:$policyId:1:2026-11-02", "O:42"))

        assertTrue(repository.snapshot().skips.isEmpty())
        assertEquals(0, changes)
    }

    @Test
    fun committedWriteStillRearmsWhenTheCallerIsCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var rearmed = false
        val slow = ScheduleRepository(db.scheduleDao()) {
            started.complete(Unit)
            release.await()
            rearmed = true
        }
        val job = launch { slow.saveOneOff(OneOffEntity(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(7, 0))) }
        started.await()
        // The screen is left mid-save: the write is committed, the re-arm must still finish.
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()

        assertEquals(1, slow.snapshot().oneOffs.size)
        assertTrue(rearmed)
    }

    @Test
    fun cancellingDuringTheDatabaseWriteStillCommitsAndRearms() = runBlocking {
        val inWrite = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : ScheduleDao by db.scheduleDao() {
            override suspend fun saveOneOff(oneOff: OneOffEntity): Long {
                inWrite.complete(Unit)
                release.await()
                return db.scheduleDao().saveOneOff(oneOff)
            }
        }
        var rearmed = false
        val repo = ScheduleRepository(gated) { rearmed = true }
        val job = launch { repo.saveOneOff(OneOffEntity(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(7, 0))) }
        inWrite.await()
        // Cancelled while Room is writing: the write and its re-arm go together, or neither happens.
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()

        assertEquals(1, repo.snapshot().oneOffs.size)
        assertTrue(rearmed)
    }

    @Test
    fun snapshotCarriesTheDefaultOneOffLabel() = runBlocking {
        val repository = ScheduleRepository(db.scheduleDao(), defaultOneOffLabel = { "Sveglia" }) {}
        assertEquals("Sveglia", repository.snapshot().defaultOneOffLabel)
        assertEquals("Sveglia", repository.snapshots.first().defaultOneOffLabel)
    }
}
