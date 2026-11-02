package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ScheduleEngineTest {
    private val zone = ZoneId.of("Europe/Rome")

    private fun at(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private fun rule(id: Long, time: String, vararg days: DayOfWeek, label: String? = null) =
        Rule(id, LocalTime.parse(time), days.toSet(), label = label)

    private val work = Policy(
        id = 1,
        name = "Work",
        rules = listOf(
            rule(10, "07:55", MONDAY, TUESDAY, WEDNESDAY),
            rule(11, "08:55", THURSDAY, FRIDAY),
        ),
    )

    private fun upcoming(snapshot: ScheduleSnapshot, from: Instant, limit: Int = 10, zone: ZoneId = this.zone) =
        ScheduleEngine.upcoming(snapshot, from, zone, limit)

    private fun List<Occurrence>.times() = map { it.at.toLocalDateTime().toString() }

    @Test
    fun `work policy merges its two rules into one weekly schedule`() {
        val result = upcoming(ScheduleSnapshot(policies = listOf(work)), at("2026-11-02", "00:00"), limit = 6)
        assertEquals(
            listOf("2026-11-02T07:55", "2026-11-03T07:55", "2026-11-04T07:55", "2026-11-05T08:55", "2026-11-06T08:55", "2026-11-09T07:55"),
            result.times(),
        )
        assertEquals("Work", result.first().label)
        assertEquals("P:1:10:2026-11-02", result.first().key)
    }

    @Test
    fun `occurrences are strictly after from`() {
        val result = upcoming(ScheduleSnapshot(policies = listOf(work)), at("2026-11-02", "07:55"), limit = 1)
        assertEquals(listOf("2026-11-03T07:55"), result.times())
    }

    @Test
    fun `disabled policy never rings`() {
        val result = upcoming(ScheduleSnapshot(policies = listOf(work.copy(enabled = false))), at("2026-11-02", "00:00"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `policy only rings inside its date window`() {
        val windowed = work.copy(startDate = LocalDate.parse("2026-11-04"), endDate = LocalDate.parse("2026-11-05"))
        val result = upcoming(ScheduleSnapshot(policies = listOf(windowed)), at("2026-11-02", "00:00"))
        assertEquals(listOf("2026-11-04T07:55", "2026-11-05T08:55"), result.times())
    }

    @Test
    fun `paused days are silent`() {
        val paused = work.copy(pauses = listOf(Pause(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"))))
        val result = upcoming(ScheduleSnapshot(policies = listOf(paused)), at("2026-11-02", "00:00"), limit = 3)
        assertEquals(listOf("2026-11-02T07:55", "2026-11-05T08:55", "2026-11-06T08:55"), result.times())
    }

    @Test
    fun `skip removes one day of one policy`() {
        val snapshot = ScheduleSnapshot(policies = listOf(work), skips = listOf(Skip(1, LocalDate.parse("2026-11-03"))))
        val result = upcoming(snapshot, at("2026-11-02", "00:00"), limit = 3)
        assertEquals(listOf("2026-11-02T07:55", "2026-11-04T07:55", "2026-11-05T08:55"), result.times())
    }

    @Test
    fun `replace-day one-off suppresses the policy that day`() {
        val oneOff = OneOff(5, LocalDate.parse("2026-11-05"), LocalTime.parse("09:30"), "Late start", OneOffMode.REPLACE_DAY)
        val result = upcoming(ScheduleSnapshot(listOf(work), listOf(oneOff)), at("2026-11-04", "00:00"), limit = 3)
        assertEquals(listOf("2026-11-04T07:55", "2026-11-05T09:30", "2026-11-06T08:55"), result.times())
        assertEquals("Late start", result[1].label)
        assertEquals("O:5", result[1].key)
    }

    @Test
    fun `additive one-off rings alongside the policy`() {
        val oneOff = OneOff(5, LocalDate.parse("2026-11-05"), LocalTime.parse("09:30"), null, OneOffMode.ADDITIVE)
        val result = upcoming(ScheduleSnapshot(listOf(work), listOf(oneOff)), at("2026-11-05", "00:00"), limit = 2)
        assertEquals(listOf("2026-11-05T08:55", "2026-11-05T09:30"), result.times())
        assertEquals(ScheduleEngine.DEFAULT_ONE_OFF_LABEL, result[1].label)
    }

    @Test
    fun `lone one-off rings once and never again`() {
        val oneOff = OneOff(5, LocalDate.parse("2026-11-05"), LocalTime.parse("09:30"))
        val result = upcoming(ScheduleSnapshot(oneOffs = listOf(oneOff)), at("2026-11-02", "00:00"))
        assertEquals(listOf("2026-11-05T09:30"), result.times())
    }

    @Test
    fun `alarms at the same instant are merged`() {
        val gym = OneOff(7, LocalDate.parse("2026-11-02"), LocalTime.parse("07:55"), "Gym", OneOffMode.ADDITIVE)
        val result = upcoming(ScheduleSnapshot(listOf(work), listOf(gym)), at("2026-11-02", "00:00"), limit = 1)
        assertEquals("Work · Gym", result.single().label)
        assertEquals(listOf("P:1:10:2026-11-02", "O:7"), result.single().sources.map { it.key })
    }

    @Test
    fun `time in the spring-forward gap moves forward`() {
        val daily = Policy(2, "Night", rules = listOf(Rule(20, LocalTime.parse("02:30"), DayOfWeek.entries.toSet())))
        val result = upcoming(ScheduleSnapshot(policies = listOf(daily)), at("2027-03-27", "12:00"), limit = 2)
        assertEquals(listOf("2027-03-28T03:30", "2027-03-29T02:30"), result.times())
    }

    @Test
    fun `time in the fall-back overlap rings once at the earlier offset`() {
        val daily = Policy(2, "Night", rules = listOf(Rule(20, LocalTime.parse("02:30"), DayOfWeek.entries.toSet())))
        val result = upcoming(ScheduleSnapshot(policies = listOf(daily)), at("2026-10-25", "00:00"), limit = 2)
        assertEquals(listOf("2026-10-25T02:30", "2026-10-26T02:30"), result.times())
        assertEquals(ZoneOffset.ofHours(2), result.first().at.offset)
    }

    @Test
    fun `local times follow the given zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val result = upcoming(ScheduleSnapshot(policies = listOf(work)), Instant.parse("2026-11-01T15:00:00Z"), limit = 1, zone = tokyo)
        assertEquals("2026-11-02T07:55", result.single().at.toLocalDateTime().toString())
        assertEquals(tokyo, result.single().at.zone)
    }

    @Test
    fun `empty or expired schedules produce nothing`() {
        assertTrue(upcoming(ScheduleSnapshot(), at("2026-11-02", "00:00")).isEmpty())
        val expired = work.copy(endDate = LocalDate.parse("2026-10-01"))
        assertTrue(upcoming(ScheduleSnapshot(policies = listOf(expired)), at("2026-11-02", "00:00")).isEmpty())
    }

    @Test
    fun `limit caps the result`() {
        assertEquals(2, upcoming(ScheduleSnapshot(policies = listOf(work)), at("2026-11-02", "00:00"), limit = 2).size)
        assertTrue(upcoming(ScheduleSnapshot(policies = listOf(work)), at("2026-11-02", "00:00"), limit = 0).isEmpty())
    }

    @Test
    fun `policyAlarmsOn ignores one-offs`() {
        val oneOff = OneOff(5, LocalDate.parse("2026-11-05"), LocalTime.parse("09:30"))
        val alarms = ScheduleEngine.policyAlarmsOn(ScheduleSnapshot(listOf(work), listOf(oneOff)), LocalDate.parse("2026-11-05"))
        assertEquals(listOf(LocalTime.parse("08:55")), alarms.map { it.time })
        assertEquals("Work", alarms.single().label)
    }
}
