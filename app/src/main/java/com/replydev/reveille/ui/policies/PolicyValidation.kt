package com.replydev.reveille.ui.policies

import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.RuleEntity
import java.time.LocalDate

fun validatePolicy(
    name: String,
    startDate: LocalDate?,
    endDate: LocalDate?,
    rules: List<RuleEntity>,
    pauses: List<PauseEntity>,
): List<String> = buildList {
    if (name.isBlank()) add("Give the policy a name.")
    if (rules.isEmpty()) add("Add at least one alarm time.")
    if (rules.any { it.days.isEmpty() }) add("Every alarm time needs at least one weekday.")
    if (startDate != null && endDate != null && endDate.isBefore(startDate)) add("The end date is before the start date.")
    if (pauses.any { it.toDate.isBefore(it.fromDate) }) add("A pause ends before it starts.")
}
