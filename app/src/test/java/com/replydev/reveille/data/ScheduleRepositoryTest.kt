package com.replydev.reveille.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
