package com.replydev.reveille.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ArmPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val everyDay = DayOfWeek.entries.toSet()

    private fun at(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private fun daily(vararg times: String) = ScheduleSnapshot(
        policies = listOf(
            Policy(1, "Work", rules = times.mapIndexed { i, t -> Rule(10L + i, LocalTime.parse(t), everyDay) }),
        ),
    )

    /** The armed form of the occurrence the snapshot has at [date] [time]. */
    private fun occurrence(snapshot: ScheduleSnapshot, date: String, time: String): Armed =
        ScheduleEngine.between(snapshot, at(date, time), at(date, time), zone).single().toArmed()

    private fun decide(
        snapshot: ScheduleSnapshot,
        now: Instant,
        previous: Armed?,
        snooze: Armed? = null,
        handled: Set<String> = emptySet(),
    ) = ArmPlanner.decide(snapshot, zone, now, previous, snooze) { it.key in handled }

    @Test
    fun `arms the next occurrence`() {
        val snapshot = daily("07:00")
        val decision = decide(snapshot, at("2026-11-02", "06:00"), previous = null)
        val next = occurrence(snapshot, "2026-11-02", "07:00")
        assertEquals(ArmDecision(next, next.at, emptyList()), decision)
    }

    @Test
    fun `arms nothing when nothing is scheduled`() {
        assertEquals(ArmDecision(null, null, emptyList()), decide(ScheduleSnapshot(), at("2026-11-02", "06:00"), null))
    }

    @Test
    fun `future snooze earlier than the next occurrence wins`() {
        val snooze = Armed.snooze(at("2026-11-02", "06:09"), "Work")
        val decision = decide(daily("07:00"), at("2026-11-02", "06:00"), previous = null, snooze = snooze)
        assertEquals(snooze, decision.armed)
        assertEquals(snooze.at, decision.triggerAt)
    }

    @Test
    fun `future previous alarm is simply replaced by the current schedule`() {
        val snapshot = daily("07:00")
        val decision = decide(snapshot, at("2026-11-02", "06:00"), previous = Armed("O:9", at("2026-11-02", "06:30"), "Old"))
        assertEquals(occurrence(snapshot, "2026-11-02", "07:00"), decision.armed)
        assertTrue(decision.missed.isEmpty())
    }

    @Test
    fun `handled previous with nothing overdue arms the next occurrence`() {
        val snapshot = daily("07:00")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val decision = decide(snapshot, at("2026-11-02", "07:01"), previous, handled = setOf(previous.key))
        val next = occurrence(snapshot, "2026-11-03", "07:00")
        assertEquals(ArmDecision(next, next.at, emptyList()), decision)
    }

    @Test
    fun `unfired alarm within the late window is caught up`() {
        val snapshot = daily("07:00")
        val now = at("2026-11-02", "07:05")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val decision = decide(snapshot, now, previous)
        assertEquals(ArmDecision(previous, now.plus(ArmPlanner.CATCH_UP_DELAY), emptyList()), decision)
    }

    @Test
    fun `two overdue alarms in one reboot are both caught up in turn`() {
        val snapshot = daily("07:00", "07:10")
        val first = occurrence(snapshot, "2026-11-02", "07:00")
        val second = occurrence(snapshot, "2026-11-02", "07:10")
        val now = at("2026-11-02", "07:15")

        val one = decide(snapshot, now, previous = first)
        assertEquals(ArmDecision(first, now.plus(ArmPlanner.CATCH_UP_DELAY), emptyList()), one)

        val later = now.plusSeconds(2)
        val two = decide(snapshot, later, previous = one.armed, handled = setOf(first.key))
        assertEquals(ArmDecision(second, later.plus(ArmPlanner.CATCH_UP_DELAY), emptyList()), two)

        val after = later.plusSeconds(60)
        val three = decide(snapshot, after, previous = two.armed, handled = setOf(first.key, second.key))
        val next = occurrence(snapshot, "2026-11-03", "07:00")
        assertEquals(ArmDecision(next, next.at, emptyList()), three)
    }

    @Test
    fun `long outage reports every lost occurrence in order and arms the next`() {
        val snapshot = daily("07:00")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val decision = decide(snapshot, at("2026-11-04", "08:00"), previous)
        assertEquals(
            listOf("2026-11-02", "2026-11-03", "2026-11-04").map { occurrence(snapshot, it, "07:00") },
            decision.missed,
        )
        val next = occurrence(snapshot, "2026-11-05", "07:00")
        assertEquals(next, decision.armed)
        assertEquals(next.at, decision.triggerAt)
    }

    @Test
    fun `older overdue alarms are missed while the latest late one is caught up`() {
        val snapshot = daily("07:00", "07:40")
        val now = at("2026-11-02", "07:45")
        val decision = decide(snapshot, now, previous = occurrence(snapshot, "2026-11-02", "07:00"))
        assertEquals(occurrence(snapshot, "2026-11-02", "07:40"), decision.armed)
        assertEquals(now.plus(ArmPlanner.CATCH_UP_DELAY), decision.triggerAt)
        assertEquals(listOf(occurrence(snapshot, "2026-11-02", "07:00")), decision.missed)
    }

    @Test
    fun `past snooze hidden behind a caught-up alarm is caught up next`() {
        val snapshot = daily("07:05")
        val alarm = occurrence(snapshot, "2026-11-02", "07:05")
        val snooze = Armed.snooze(at("2026-11-02", "07:09"), "Work")
        val now = at("2026-11-02", "07:15")

        val one = decide(snapshot, now, previous = alarm, snooze = snooze)
        assertEquals(alarm, one.armed)

        val later = now.plusSeconds(2)
        val two = decide(snapshot, later, previous = alarm, snooze = snooze, handled = setOf(alarm.key))
        assertEquals(ArmDecision(snooze, later.plus(ArmPlanner.CATCH_UP_DELAY), emptyList()), two)
    }

    @Test
    fun `past snooze behind an alarm is missed when beyond the window`() {
        val snapshot = daily("07:05")
        val alarm = occurrence(snapshot, "2026-11-02", "07:05")
        val snooze = Armed.snooze(at("2026-11-02", "07:09"), "Work")
        val decision = decide(snapshot, at("2026-11-02", "07:45"), previous = alarm, snooze = snooze)
        assertEquals(listOf(alarm, snooze), decision.missed)
        assertEquals(occurrence(snapshot, "2026-11-03", "07:05"), decision.armed)
    }

    @Test
    fun `past snooze beyond the window is reported missed`() {
        val snapshot = daily("07:00")
        val snooze = Armed.snooze(at("2026-11-02", "07:09"), "Work")
        val decision = decide(snapshot, at("2026-11-02", "07:40"), previous = null, snooze = snooze)
        assertEquals(listOf(snooze), decision.missed)
        assertEquals(occurrence(snapshot, "2026-11-03", "07:00"), decision.armed)
    }

    @Test
    fun `deleted one-off is not rung`() {
        val deleted = Armed("O:5", at("2026-11-02", "07:00"), "Dentist", listOf("O:5"))
        val decision = decide(ScheduleSnapshot(), at("2026-11-02", "07:05"), previous = deleted)
        assertEquals(ArmDecision(null, null, emptyList()), decision)
    }

    @Test
    fun `exactly the late window ago is still caught up`() {
        val snapshot = daily("07:00")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val now = previous.at.plus(ArmPlanner.LATE_RING_WINDOW)
        val decision = decide(snapshot, now, previous)
        assertEquals(ArmDecision(previous, now.plus(ArmPlanner.CATCH_UP_DELAY), emptyList()), decision)
    }

    @Test
    fun `one millisecond beyond the late window is missed`() {
        val snapshot = daily("07:00")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val now = previous.at.plus(ArmPlanner.LATE_RING_WINDOW).plus(Duration.ofMillis(1))
        val decision = decide(snapshot, now, previous)
        assertEquals(listOf(previous), decision.missed)
        assertEquals(occurrence(snapshot, "2026-11-03", "07:00"), decision.armed)
    }

    @Test
    fun `handled overdue alarms are neither rung nor reported`() {
        val snapshot = daily("07:00")
        val previous = occurrence(snapshot, "2026-11-02", "07:00")
        val decision = decide(snapshot, at("2026-11-02", "09:00"), previous, handled = setOf(previous.key))
        val next = occurrence(snapshot, "2026-11-03", "07:00")
        assertEquals(ArmDecision(next, next.at, emptyList()), decision)
    }
}
