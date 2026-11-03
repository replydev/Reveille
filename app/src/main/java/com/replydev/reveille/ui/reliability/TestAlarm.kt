package com.replydev.reveille.ui.reliability

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.engine.Armed
import java.time.Instant

enum class TestAlarmResult {
    /** The armed alarm is the test alarm (possibly merged with another alarm at the same minute). */
    SET,

    /** Another alarm is armed before the test; the test is armed once that one has rung. */
    AFTER_ANOTHER,

    /** Nothing shows the test alarm reached AlarmManager. */
    NOT_CONFIRMED,
}

/** Reads the armed state right after saving the test alarm [testKey] at [testAt]. */
fun testAlarmResult(armed: Armed?, testKey: String, testAt: Instant, now: Instant): TestAlarmResult = when {
    armed == null -> TestAlarmResult.NOT_CONFIRMED
    armed.at == testAt && (armed.key == testKey || testKey in armed.sourceKeys) -> TestAlarmResult.SET
    armed.at.isBefore(testAt) && armed.at.isAfter(now) -> TestAlarmResult.AFTER_ANOTHER
    else -> TestAlarmResult.NOT_CONFIRMED
}

/** What the user is told after starting a test alarm at [time]; [earlier] is the time of the alarm armed before it. */
fun testAlarmMessage(res: Resources, result: TestAlarmResult, time: String, earlier: String?): String = when (result) {
    TestAlarmResult.SET -> res.getString(R.string.health_test_set, time)
    TestAlarmResult.AFTER_ANOTHER -> res.getString(R.string.health_test_after_another, time, earlier.orEmpty())
    TestAlarmResult.NOT_CONFIRMED -> res.getString(R.string.health_test_not_confirmed, time)
}
