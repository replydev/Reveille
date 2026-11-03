package com.replydev.reveille.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.alarm.RingTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingThemePickerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyThemeHasTheSpecNameAndHint() {
        val names = RingTheme.entries.map { context.getString(it.nameRes) }
        val hints = RingTheme.entries.map { context.getString(it.hintRes) }
        assertEquals(listOf("Dawn", "Midnight", "Policy colour", "Horizon", "Paper"), names)
        assertEquals(listOf("Default · amber", "Dim, for dark rooms", "Tells routines apart", "Sunrise in bands", "Light and bold"), hints)
    }

    @Test
    fun policyRowsFollowThePoliciesAndIgnoreStaleOverrides() {
        val rows = policyThemeRows(
            policies = listOf(1L to "Work", 2L to "Gym", 3L to "Night shifts"),
            overrides = mapOf(1L to RingTheme.POLICY_COLOUR, 3L to RingTheme.MIDNIGHT, 9L to RingTheme.PAPER),
        )
        assertEquals(
            listOf(
                PolicyThemeRow(1, "Work", RingTheme.POLICY_COLOUR),
                PolicyThemeRow(2, "Gym", null),
                PolicyThemeRow(3, "Night shifts", RingTheme.MIDNIGHT),
            ),
            rows,
        )
    }
}
