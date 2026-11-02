package com.replydev.reveille.ui.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.replydev.reveille.container
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.DateButton
import com.replydev.reveille.ui.components.OptionalDateRow
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.SectionTitle
import com.replydev.reveille.ui.components.TimeButton
import com.replydev.reveille.ui.components.WeekdayChips
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.components.runReporting
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private const val MAX_INTERVAL_WEEKS = 8

private fun newRule() = RuleEntity(
    policyId = 0,
    time = LocalTime.of(7, 0),
    days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    anchorDate = LocalDate.now(),
)

@Composable
fun PolicyEditorScreen(id: Long, onDone: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    val rules = remember { mutableStateListOf<RuleEntity>() }
    val pauses = remember { mutableStateListOf<PauseEntity>() }

    LaunchedEffect(id) {
        val existing = if (id == 0L) null else container.repository.policy(id)
        if (existing == null) {
            rules.add(newRule())
        } else {
            name = existing.policy.name
            enabled = existing.policy.enabled
            startDate = existing.policy.startDate
            endDate = existing.policy.endDate
            rules.addAll(existing.rules.sortedBy { it.time })
            pauses.addAll(existing.pauses)
        }
        loaded = true
    }

    val errors = validatePolicy(name, startDate, endDate, rules, pauses)

    /** One write at a time: a double tap on Save must not create the policy twice. */
    fun write(failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = snackbar.runReporting(failure, action)
            busy = false
            if (ok) onDone()
        }
    }

    ScreenScaffold(
        title = if (id == 0L) "New policy" else "Edit policy",
        onBack = onDone,
        snackbarHostState = snackbar,
        actions = {
            if (id != 0L) {
                TextButton(enabled = loaded && !busy, onClick = { confirmDelete = true }) { Text("Delete") }
            }
            TextButton(
                enabled = loaded && !busy && errors.isEmpty(),
                onClick = {
                    write("Could not save the policy.") {
                        container.repository.savePolicy(
                            PolicyEntity(id = id, name = name.trim(), enabled = enabled, startDate = startDate, endDate = endDate),
                            rules.toList(),
                            pauses.toList(),
                        )
                    }
                },
            ) { Text("Save") }
        },
    ) { padding ->
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete policy?",
                text = "All of its alarms will stop ringing.",
                confirmLabel = "Delete",
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    write("Could not delete the policy.") { container.repository.deletePolicy(id) }
                },
            )
        }
        if (!loaded) return@ScreenScaffold
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            item { OptionalDateRow("Active from", startDate) { startDate = it } }
            item { OptionalDateRow("Active until", endDate) { endDate = it } }
            item { SectionTitle("Alarm times") }
            itemsIndexed(rules) { index, rule ->
                RuleCard(rule, onChange = { rules[index] = it }, onRemove = { rules.removeAt(index) })
            }
            item { OutlinedButton(onClick = { rules.add(newRule()) }) { Text("Add alarm time") } }
            item { SectionTitle("Pauses") }
            itemsIndexed(pauses) { index, pause ->
                PauseRow(pause, onChange = { pauses[index] = it }, onRemove = { pauses.removeAt(index) })
            }
            item {
                OutlinedButton(onClick = {
                    val today = LocalDate.now()
                    pauses.add(PauseEntity(policyId = id, fromDate = today, toDate = today.plusDays(7)))
                }) { Text("Add pause") }
            }
            if (errors.isNotEmpty()) {
                item {
                    Column {
                        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: RuleEntity, onChange: (RuleEntity) -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(rule.time, onChange = { onChange(rule.copy(time = it)) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            WeekdayChips(rule.days) { onChange(rule.copy(days = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every")
                TextButton(onClick = { onChange(rule.copy(intervalWeeks = (rule.intervalWeeks - 1).coerceAtLeast(1))) }) { Text("−") }
                Text("${rule.intervalWeeks}")
                TextButton(onClick = { onChange(rule.copy(intervalWeeks = (rule.intervalWeeks + 1).coerceAtMost(MAX_INTERVAL_WEEKS))) }) { Text("+") }
                Text(if (rule.intervalWeeks == 1) "week" else "weeks")
            }
            if (rule.intervalWeeks > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Including the week of", Modifier.weight(1f))
                    DateButton(rule.anchorDate, onChange = { onChange(rule.copy(anchorDate = it)) })
                }
            }
            OutlinedTextField(
                value = rule.label.orEmpty(),
                onValueChange = { onChange(rule.copy(label = it.ifBlank { null })) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PauseRow(pause: PauseEntity, onChange: (PauseEntity) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DateButton(pause.fromDate, onChange = { onChange(pause.copy(fromDate = it)) })
        Text("→")
        DateButton(pause.toDate, onChange = { onChange(pause.copy(toDate = it)) })
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
