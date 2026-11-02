package com.replydev.reveille.ui.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.container
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.rememberToday

@Composable
fun PoliciesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val container = LocalContext.current.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val today = rememberToday()
    val policies by container.repository.policies.collectAsStateWithLifecycle(initialValue = null)
    ScreenScaffold(
        title = "Policies",
        onBack = onBack,
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { onOpen(0) }) { Text("New policy") } },
        snackbarHostState = snackbar,
    ) { padding ->
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val list = policies ?: return@LazyColumn
            if (list.isEmpty()) {
                item { Text("No policies yet. A policy groups recurring alarms, for example \"Work\".") }
            }
            items(list, key = { it.policy.id }) { item ->
                PolicyCard(
                    item = item,
                    status = policyStatus(item.policy, item.pauses, today),
                    onOpen = { onOpen(item.policy.id) },
                    onEnabledChange = { enabled ->
                        session.write(
                            "Could not update the policy.",
                            guarded = false,
                            action = { container.repository.setPolicyEnabled(item.policy.id, enabled) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PolicyCard(item: PolicyWithChildren, status: String?, onOpen: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.policy.name, style = MaterialTheme.typography.titleMedium)
                status?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
                item.rules.sortedBy { it.time }.forEach { Text(ruleSummary(it), style = MaterialTheme.typography.bodyMedium) }
            }
            Switch(checked = item.policy.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

fun ruleSummary(rule: RuleEntity): String = buildString {
    append(TimeText.time(rule.time))
    append("  ")
    append(TimeText.days(rule.days))
    if (rule.intervalWeeks > 1) append(" · every ${rule.intervalWeeks} weeks")
    rule.label?.let { append(" · $it") }
}
