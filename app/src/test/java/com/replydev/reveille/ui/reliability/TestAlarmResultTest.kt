package com.replydev.reveille.ui.reliability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.engine.Armed
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TestAlarmResultTest {
    private val now = Instant.parse("2026-11-02T06:00:30Z")
    private val testAt = Instant.parse("2026-11-02T06:02:00Z")
    private val testKey = "O:7"

    @Test
    fun armedTestAlarmIsSet() {
        assertEquals(TestAlarmResult.SET, testAlarmResult(Armed(testKey, testAt, "Test alarm", listOf(testKey)), testKey, testAt, now))
        // Merged with a policy alarm at the same minute: keyed by the policy alarm, but it includes the test.
        assertEquals(TestAlarmResult.SET, testAlarmResult(Armed("P:1:2:2026-11-02", testAt, "Work", listOf("P:1:2:2026-11-02", testKey)), testKey, testAt, now))
    }

    @Test
    fun earlierFutureAlarmMeansTheTestComesAfterIt() {
        val earlier = Armed("P:1:2:2026-11-02", Instant.parse("2026-11-02T06:01:00Z"), "Work")
        assertEquals(TestAlarmResult.AFTER_ANOTHER, testAlarmResult(earlier, testKey, testAt, now))
    }

    @Test
    fun anythingElseIsNotConfirmed() {
        assertEquals(TestAlarmResult.NOT_CONFIRMED, testAlarmResult(null, testKey, testAt, now))
        assertEquals(TestAlarmResult.NOT_CONFIRMED, testAlarmResult(Armed("O:1", testAt.plusSeconds(3600), "Later"), testKey, testAt, now))
        assertEquals(TestAlarmResult.NOT_CONFIRMED, testAlarmResult(Armed("O:1", now.minusSeconds(60), "Overdue"), testKey, testAt, now))
        // Same minute but not the test alarm (refused re-arm left an old alarm): not proof.
        assertEquals(TestAlarmResult.NOT_CONFIRMED, testAlarmResult(Armed("O:1", testAt, "Other"), testKey, testAt, now))
    }

    @Test
    fun messagesSayWhatHappenedToTheTest() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("Test alarm set for 07:02. Lock your phone now.", testAlarmMessage(res, TestAlarmResult.SET, "07:02", null))
        assertEquals(
            "Test alarm saved for 07:02; it is set once the alarm at 07:01 has rung.",
            testAlarmMessage(res, TestAlarmResult.AFTER_ANOTHER, "07:02", "07:01"),
        )
        assertEquals(
            "Test alarm saved for 07:02, but the system did not confirm it. Fix the items above.",
            testAlarmMessage(res, TestAlarmResult.NOT_CONFIRMED, "07:02", null),
        )
    }
}
