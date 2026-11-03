package com.replydev.reveille.ui.policies

import androidx.annotation.StringRes
import com.replydev.reveille.R
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.RuleEntity
import java.time.LocalDate

/** Why a draft policy cannot be saved. */
enum class PolicyError(@StringRes val message: Int) {
    NO_NAME(R.string.policy_error_no_name),
    NO_RULES(R.string.policy_error_no_rules),
    RULE_WITHOUT_DAYS(R.string.policy_error_rule_without_days),
    END_BEFORE_START(R.string.policy_error_end_before_start),
    PAUSE_ENDS_BEFORE_START(R.string.policy_error_pause_reversed),
}

fun validatePolicy(
    name: String,
    startDate: LocalDate?,
    endDate: LocalDate?,
    rules: List<RuleEntity>,
    pauses: List<PauseEntity>,
): List<PolicyError> = buildList {
    if (name.isBlank()) add(PolicyError.NO_NAME)
    if (rules.isEmpty()) add(PolicyError.NO_RULES)
    if (rules.any { it.days.isEmpty() }) add(PolicyError.RULE_WITHOUT_DAYS)
    if (startDate != null && endDate != null && endDate.isBefore(startDate)) add(PolicyError.END_BEFORE_START)
    if (pauses.any { it.toDate.isBefore(it.fromDate) }) add(PolicyError.PAUSE_ENDS_BEFORE_START)
}
