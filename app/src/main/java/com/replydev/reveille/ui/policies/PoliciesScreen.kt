package com.replydev.reveille.ui.policies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.container
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.MiniDayCells
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleFab
import com.replydev.reveille.ui.components.ReveilleSwitch
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.rememberToday
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.animatedItem
import com.replydev.reveille.ui.theme.policyColor
import com.replydev.reveille.ui.theme.policyTint
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

@Composable
fun PoliciesScreen(onOpen: (Long) -> Unit) {
    val container = LocalContext.current.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val today = rememberToday()
    val policies by container.repository.policies.collectAsStateWithLifecycle(initialValue = null)
    val snapshot by container.repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(Instant.now(), snapshot) {
        value = Instant.now()
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    TabScaffold(
        floatingActionButton = { ReveilleFab("New policy", onClick = { onOpen(0) }) },
        snackbarHostState = snackbar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                TabHeader("Policies", bottom = 4.dp) {
                    Text(
                        "Named groups of recurring alarms. Turn a whole routine on or off in one tap.",
                        Modifier.padding(top = 4.dp),
                        style = ReveilleTheme.type.paragraph,
                        color = ReveilleTheme.colors.muted,
                    )
                }
            }
            val list = policies ?: return@LazyColumn
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No policies yet. A policy groups recurring alarms, for example \"Work\".",
                        Modifier.animatedItem(this).padding(horizontal = 20.dp),
                        style = ReveilleTheme.type.body,
                    )
                }
            }
            items(list, key = { it.policy.id }) { item ->
                val status = policyStatus(item.policy, item.pauses, today)
                PolicyCard(
                    item = item,
                    status = statusLine(status, if (status == null) nextLabel(item, snapshot, now) else null),
                    highlight = isPauseStatus(status),
                    onOpen = { onOpen(item.policy.id) },
                    onEnabledChange = { enabled ->
                        session.write(
                            "Could not update the policy.",
                            guarded = false,
                            action = { container.repository.setPolicyEnabled(item.policy.id, enabled) },
                        )
                    },
                    modifier = Modifier.animatedItem(this).padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** "Tue 07:55" for the policy's next alarm in the lookahead, judged with the rest of the schedule. */
private fun nextLabel(item: PolicyWithChildren, snapshot: ScheduleSnapshot?, now: Instant): String? {
    val zone = ZoneId.systemDefault()
    val next = nextPolicyAlarm(item.toModel(), snapshot ?: return null, now, zone) ?: return null
    return TimeText.dayAndTime(next.at.toInstant(), now, zone)
}

@Composable
private fun PolicyCard(
    item: PolicyWithChildren,
    status: String,
    highlight: Boolean,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val id = item.policy.id
    ReveilleCard(
        modifier.fillMaxWidth().alpha(if (item.policy.enabled) 1f else 0.6f),
        onClick = onOpen,
        radius = 24.dp,
        padding = PaddingValues(18.dp),
        spacing = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(policyTint(id), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                PolicyDot(policyColor(id), size = 12.dp, radius = 3.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.policy.name, style = type.titleS)
                Text(status, style = type.caption, color = if (highlight) colors.accent else colors.muted)
            }
            ReveilleSwitch(checked = item.policy.enabled, onCheckedChange = onEnabledChange)
        }
        val rules = item.rules.sortedBy { it.time }
        if (rules.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().topDivider(colors.outline).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rules.forEach { RuleRow(it, policyColor(id)) }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: RuleEntity, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(TimeText.time(rule.time), Modifier.width(66.dp), style = ReveilleTheme.type.timeRule)
        Spacer(Modifier.width(12.dp))
        MiniDayCells(rule.days, color)
        Spacer(Modifier.weight(1f).width(12.dp))
        ruleNote(rule)?.let { Text(it, style = ReveilleTheme.type.small, color = ReveilleTheme.colors.muted, maxLines = 1) }
    }
}
