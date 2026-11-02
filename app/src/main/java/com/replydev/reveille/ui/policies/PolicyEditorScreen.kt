package com.replydev.reveille.ui.policies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.replydev.reveille.ui.components.LeaveWhenSaved
import com.replydev.reveille.ui.components.OptionalDateRow
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.SectionTitle
import com.replydev.reveille.ui.components.TimeButton
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.WeekdayChips
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.animatedItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val MAX_INTERVAL_WEEKS = 8

private fun newRule() = RuleDraft(
    newDraftKey(),
    RuleEntity(
        policyId = 0,
        time = LocalTime.of(7, 0),
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        anchorDate = LocalDate.now(),
    ),
)

@Composable
fun PolicyEditorScreen(onDone: () -> Unit) {
    val container = LocalContext.current.container
    val repository = container.repository
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    LeaveWhenSaved(session, onDone)

    // The draft survives rotation, theme changes and process death; it is loaded from the database only once.
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var startDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var endDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val rules = rememberSaveable(saver = RuleDraftsSaver) { emptyRuleDrafts() }
    val pauses = rememberSaveable(saver = PauseDraftsSaver) { emptyPauseDrafts() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val id = session.editId
        val existing = if (id == 0L) null else repository.policy(id)
        if (existing == null) {
            rules.add(newRule())
            if (id != 0L) {
                session.editId = 0L
                session.message(UiMessage("This policy was deleted. Saving creates a new one."))
            }
        } else {
            name = existing.policy.name
            enabled = existing.policy.enabled
            startDate = existing.policy.startDate
            endDate = existing.policy.endDate
            rules.addAll(existing.rules.sortedBy { it.time }.map { RuleDraft(newDraftKey(), it) })
            pauses.addAll(existing.pauses.map { PauseDraft(newDraftKey(), it) })
        }
        loaded = true
    }

    val editId = session.editId
    val ruleList = rules.map { it.rule }
    val pauseList = pauses.map { it.pause }
    val errors = validatePolicy(name, startDate, endDate, ruleList, pauseList)
    val draftPolicy = PolicyEntity(id = editId, name = name.trim(), enabled = enabled, startDate = startDate, endDate = endDate)
    val warning = if (loaded && errors.isEmpty()) upcomingWarning(draftPolicy, ruleList, pauseList, Instant.now(), ZoneId.systemDefault()) else null

    ScreenScaffold(
        title = if (editId == 0L) "New policy" else "Edit policy",
        onBack = onDone,
        snackbarHostState = snackbar,
        actions = {
            if (editId != 0L) {
                TextButton(enabled = loaded && !session.busy, onClick = { confirmDelete = true }) { Text("Delete") }
            }
            TextButton(
                enabled = loaded && !session.busy && errors.isEmpty(),
                onClick = {
                    val toSave = ruleList.map { it.copy(label = it.label?.trim()?.ifEmpty { null }) }
                    session.write(
                        "Could not save the policy.",
                        action = { repository.savePolicy(draftPolicy, toSave, pauseList) },
                        onSuccess = { savedId ->
                            // Set before anything else: if the screen stays open, saving again updates this policy.
                            session.editId = savedId
                            session.saved = true
                        },
                    )
                },
            ) { Text("Save") }
        },
    ) { padding ->
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete policy?",
                text = "Its upcoming alarms will not ring. An alarm that is already snoozed still rings.",
                confirmLabel = "Delete",
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    val target = editId
                    session.write(
                        "Could not delete the policy.",
                        action = { repository.deletePolicy(target) },
                        onSuccess = { session.saved = true },
                    )
                },
            )
        }
        if (!loaded) return@ScreenScaffold
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "enabled") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            item(key = "start") { OptionalDateRow("Active from", startDate) { startDate = it } }
            item(key = "end") { OptionalDateRow("Active until", endDate) { endDate = it } }
            item(key = "rules-title") { SectionTitle("Alarm times") }
            itemsIndexed(rules, key = { _, draft -> "rule-${draft.key}" }) { index, draft ->
                RuleCard(
                    draft.rule,
                    onChange = { rules[index] = draft.copy(rule = it) },
                    onRemove = { rules.removeAt(index) },
                    modifier = Modifier.animatedItem(this),
                )
            }
            item(key = "add-rule") { OutlinedButton(onClick = { rules.add(newRule()) }, modifier = Modifier.animatedItem(this)) { Text("Add alarm time") } }
            item(key = "pauses-title") { SectionTitle("Pauses", Modifier.animatedItem(this)) }
            itemsIndexed(pauses, key = { _, draft -> "pause-${draft.key}" }) { index, draft ->
                PauseRow(
                    draft.pause,
                    onChange = { pauses[index] = draft.copy(pause = it) },
                    onRemove = { pauses.removeAt(index) },
                    modifier = Modifier.animatedItem(this),
                )
            }
            item(key = "add-pause") {
                OutlinedButton(
                    onClick = {
                        val today = LocalDate.now()
                        pauses.add(PauseDraft(newDraftKey(), PauseEntity(policyId = editId, fromDate = today, toDate = today.plusDays(7))))
                    },
                    modifier = Modifier.animatedItem(this),
                ) { Text("Add pause") }
            }
            if (errors.isNotEmpty() || warning != null) {
                item(key = "problems") {
                    Column(Modifier.animatedItem(this).animateContentSize(Motion.size)) {
                        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: RuleEntity, onChange: (RuleEntity) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
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
            AnimatedVisibility(rule.intervalWeeks > 1, enter = Motion.expand, exit = Motion.collapse) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Including the week of", Modifier.weight(1f))
                    DateButton(rule.anchorDate, onChange = { onChange(rule.copy(anchorDate = it)) })
                }
            }
            OutlinedTextField(
                value = rule.label.orEmpty(),
                // ifEmpty, not ifBlank: a space typed between words must not vanish. Trimmed on save.
                onValueChange = { onChange(rule.copy(label = it.ifEmpty { null })) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PauseRow(pause: PauseEntity, onChange: (PauseEntity) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DateButton(pause.fromDate, onChange = { onChange(pause.copy(fromDate = it)) })
        Text("→")
        DateButton(pause.toDate, onChange = { onChange(pause.copy(toDate = it)) })
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
