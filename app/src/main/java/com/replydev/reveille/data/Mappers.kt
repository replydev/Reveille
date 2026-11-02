package com.replydev.reveille.data

import com.replydev.reveille.engine.OneOff
import com.replydev.reveille.engine.Pause
import com.replydev.reveille.engine.Policy
import com.replydev.reveille.engine.Rule
import com.replydev.reveille.engine.Skip

fun PolicyWithChildren.toModel(): Policy = Policy(
    id = policy.id,
    name = policy.name,
    enabled = policy.enabled,
    startDate = policy.startDate,
    endDate = policy.endDate,
    rules = rules.map { it.toModel() },
    pauses = pauses.map { Pause(it.fromDate, it.toDate) },
)

fun RuleEntity.toModel(): Rule = Rule(id, time, days, intervalWeeks, anchorDate, label)

fun OneOffEntity.toModel(): OneOff = OneOff(id, date, time, label, mode)

fun SkipEntity.toModel(): Skip = Skip(policyId, date)
