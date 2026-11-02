package com.replydev.reveille.ui.home

import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source

/**
 * Names of the policies whose alarms ring again because [removed] REPLACE_DAY one-offs were deleted, judged on the
 * schedule [after] the deletion (another one-off may still replace the day). Sorted by name.
 */
fun policiesRingingAgain(after: ScheduleSnapshot, removed: List<OneOff>): List<String> {
    val names = after.policies.associate { it.id to it.name }
    return removed
        .filter { it.mode == OneOffMode.REPLACE_DAY }
        .map { it.date }
        .distinct()
        .filterNot { ScheduleEngine.isDayReplaced(after, it) }
        .flatMap { ScheduleEngine.policyAlarmsOn(after, it) }
        .mapNotNull { names[(it.source as Source.PolicyRule).policyId] }
        .distinct()
        .sorted()
}

fun skipMessage(time: String, label: String, ringingAgain: List<String>): String =
    if (ringingAgain.isEmpty()) "Skipped $time $label"
    else "Skipped $time $label · ${ringingAgain.joinToString(", ")} alarms that day will ring again"
