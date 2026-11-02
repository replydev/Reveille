package com.replydev.reveille.ui.policies

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaverScope
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Serializable
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import java.time.LocalTime

class PolicyDraftTest {
    /** Accepts what a Bundle accepts for these values. */
    private val bundleScope = SaverScope { it is Serializable }
    private val day = LocalDate.parse("2026-11-02")

    @Test
    fun ruleDraftsSurviveSaveAndRestore() {
        val drafts = mutableStateListOf(
            RuleDraft(11, RuleEntity(id = 5, policyId = 2, time = LocalTime.of(7, 55), days = setOf(MONDAY, FRIDAY), intervalWeeks = 2, anchorDate = day, label = "Gym")),
            RuleDraft(12, RuleEntity(policyId = 0, time = LocalTime.of(9, 0), days = emptySet(), anchorDate = day)),
        )
        val saved = with(RuleDraftsSaver) { bundleScope.save(drafts) }!!
        assertEquals(drafts.toList(), RuleDraftsSaver.restore(saved)!!.toList())
    }

    @Test
    fun pauseDraftsSurviveSaveAndRestore() {
        val drafts = mutableStateListOf(PauseDraft(3, PauseEntity(id = 4, policyId = 2, fromDate = day, toDate = day.plusDays(3))))
        val saved = with(PauseDraftsSaver) { bundleScope.save(drafts) }!!
        assertEquals(drafts.toList(), PauseDraftsSaver.restore(saved)!!.toList())
    }
}
