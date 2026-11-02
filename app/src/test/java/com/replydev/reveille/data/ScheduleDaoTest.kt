package com.replydev.reveille.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleDaoTest {
    private lateinit var db: ReveilleDatabase
    private lateinit var dao: ScheduleDao
    private val anchor = LocalDate.parse("2026-11-02")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReveilleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scheduleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun workRules() = listOf(
        RuleEntity(policyId = 0, time = LocalTime.of(7, 55), days = setOf(MONDAY, TUESDAY, WEDNESDAY), anchorDate = anchor),
        RuleEntity(policyId = 0, time = LocalTime.of(8, 55), days = setOf(THURSDAY, FRIDAY), anchorDate = anchor),
    )

    @Test
    fun savePolicyStoresRulesAndPauses() = runBlocking {
        val pause = PauseEntity(policyId = 0, fromDate = LocalDate.parse("2026-12-20"), toDate = LocalDate.parse("2027-01-06"))
        val id = dao.savePolicy(PolicyEntity(name = "Work"), workRules(), listOf(pause))
        val saved = dao.policy(id)!!
        assertEquals("Work", saved.policy.name)
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY), saved.rules.single { it.time == LocalTime.of(7, 55) }.days)
        assertEquals(LocalDate.parse("2027-01-06"), saved.pauses.single().toDate)
    }

    @Test
    fun savePolicyReplacesRulesAndPauses() = runBlocking {
        val id = dao.savePolicy(PolicyEntity(name = "Work"), workRules(), emptyList())
        val single = RuleEntity(policyId = id, time = LocalTime.of(6, 30), days = setOf(MONDAY), anchorDate = anchor)
        dao.savePolicy(PolicyEntity(id = id, name = "Early"), listOf(single), emptyList())
        val saved = dao.policy(id)!!
        assertEquals("Early", saved.policy.name)
        assertEquals(listOf(LocalTime.of(6, 30)), saved.rules.map { it.time })
    }

    @Test
    fun deletingPolicyCascadesToChildren() = runBlocking {
        val id = dao.savePolicy(PolicyEntity(name = "Work"), workRules(), emptyList())
        dao.insertSkip(SkipEntity(id, LocalDate.parse("2026-11-03")))
        dao.deletePolicy(id)
        assertTrue(dao.policies().isEmpty())
        assertTrue(dao.skips().isEmpty())
    }

    @Test
    fun cleanupRemovesPastOneOffsAndSkips() = runBlocking {
        val id = dao.savePolicy(PolicyEntity(name = "Work"), workRules(), emptyList())
        dao.saveOneOff(OneOffEntity(date = LocalDate.parse("2026-11-01"), time = LocalTime.of(9, 0)))
        dao.saveOneOff(OneOffEntity(date = LocalDate.parse("2026-11-02"), time = LocalTime.of(9, 0)))
        dao.insertSkip(SkipEntity(id, LocalDate.parse("2026-11-01")))
        dao.deleteOneOffsBefore(LocalDate.parse("2026-11-02"))
        dao.deleteSkipsBefore(LocalDate.parse("2026-11-02"))
        assertEquals(listOf(LocalDate.parse("2026-11-02")), dao.oneOffs().map { it.date })
        assertTrue(dao.skips().isEmpty())
    }

    @Test
    fun fireLogClaimsAnOccurrenceOnce() = runBlocking {
        val log = db.fireLogDao()
        val entry = FireLogEntity(occurrenceKey = "O:1", label = "Test", scheduledAt = 1_000, firedAt = 1_000, outcome = FireOutcome.FIRED)
        assertFalse(log.exists("O:1", 1_000))
        assertTrue(log.insert(entry) != -1L)
        assertEquals(-1L, log.insert(entry))
        assertTrue(log.exists("O:1", 1_000))
        assertEquals(listOf(HandledOccurrence("O:1", 1_000)), log.handledSince(1_000))
        assertTrue(log.handledSince(1_001).isEmpty())
    }
}
