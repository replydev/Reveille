package com.replydev.reveille.ui.home

import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.Policy
import com.replydev.reveille.engine.Rule
import com.replydev.reveille.engine.ScheduleSnapshot
import org.junit.Assert.assertEquals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

class SkipMessageTest {
    private val day = LocalDate.parse("2026-11-02")
    private val everyDay = DayOfWeek.entries.toSet()
    private val work = Policy(1, "Work", rules = listOf(Rule(1, LocalTime.of(7, 0), everyDay)))
    private val gym = Policy(2, "Gym", rules = listOf(Rule(2, LocalTime.of(6, 0), everyDay)))
    private val replacing = OneOff(9, day, LocalTime.of(9, 30), "Dentist", OneOffMode.REPLACE_DAY)

    @Test
    fun skippingAReplaceDayOneOffBringsBackThatDaysPolicyAlarms() {
        val after = ScheduleSnapshot(policies = listOf(work, gym))
        val names = policiesRingingAgain(after, listOf(replacing))
        assertEquals(listOf("Gym", "Work"), names)
        assertEquals("Skipped 09:30 Dentist · Gym, Work alarms that day will ring again", skipMessage("09:30", "Dentist", names))
    }

    @Test
    fun nothingComesBackForAdditiveOneOffsOrWhileAnotherOneOffStillReplacesTheDay() {
        val additive = replacing.copy(mode = OneOffMode.ADDITIVE)
        assertEquals(emptyList<String>(), policiesRingingAgain(ScheduleSnapshot(policies = listOf(work)), listOf(additive)))
        val stillReplaced = ScheduleSnapshot(policies = listOf(work), oneOffs = listOf(replacing.copy(id = 10, time = LocalTime.of(11, 0))))
        assertEquals(emptyList<String>(), policiesRingingAgain(stillReplaced, listOf(replacing)))
        assertEquals("Skipped 09:30 Dentist", skipMessage("09:30", "Dentist", emptyList()))
    }
}
