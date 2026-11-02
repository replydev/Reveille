package com.replydev.reveille.ui.oneoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.DateButton
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.TimeButton
import com.replydev.reveille.ui.components.runReporting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A one-off must be after this moment: anything earlier would ring the moment it is saved. */
private fun isInPast(date: LocalDate, time: LocalTime): Boolean = !LocalDateTime.of(date, time).isAfter(LocalDateTime.now())

@Composable
fun OneOffEditorScreen(id: Long, onDone: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = remember { LocalDate.now() }
    // The alarm may have been deleted since the link was opened (it rang, or was skipped from the widget):
    // then this screen creates a new alarm instead of resurrecting the old id.
    var editId by remember { mutableLongStateOf(id) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(today.plusDays(1)) }
    var time by remember { mutableStateOf(LocalTime.of(7, 0)) }
    var label by remember { mutableStateOf("") }
    var replaceDay by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        if (id != 0L) {
            val existing = container.repository.oneOff(id)
            if (existing == null) {
                editId = 0L
            } else {
                date = existing.date
                time = existing.time
                label = existing.label.orEmpty()
                replaceDay = existing.mode == OneOffMode.REPLACE_DAY
            }
            loaded = true
            // Last: showSnackbar suspends until the snackbar is dismissed.
            if (existing == null) snackbar.showSnackbar("This alarm no longer exists. Saving creates a new one.")
        }
    }

    val snapshot by container.repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val sameDay = remember(snapshot, date) { snapshot?.let { ScheduleEngine.policyAlarmsOn(it, date) } ?: emptyList() }
    val replacedByOther = snapshot?.oneOffs?.any { it.date == date && it.mode == OneOffMode.REPLACE_DAY && it.id != editId } == true
    // Re-evaluated every few seconds so the warning and the Save button follow the clock while the screen is open.
    val clock by produceState(0) {
        while (true) {
            delay(5_000)
            value++
        }
    }
    val inPast = remember(date, time, clock) { isInPast(date, time) }

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
        title = if (editId == 0L) "New alarm" else "Edit alarm",
        onBack = onDone,
        snackbarHostState = snackbar,
        actions = {
            if (editId != 0L) {
                TextButton(enabled = loaded && !busy, onClick = { confirmDelete = true }) { Text("Delete") }
            }
            TextButton(
                enabled = loaded && !busy && !inPast,
                onClick = {
                    if (isInPast(date, time)) {
                        scope.launch { snackbar.showSnackbar("This time is in the past.") }
                        return@TextButton
                    }
                    write("Could not save the alarm.") {
                        container.repository.saveOneOff(
                            OneOffEntity(
                                id = editId,
                                date = date,
                                time = time,
                                label = label.trim().ifEmpty { null },
                                mode = if (replaceDay) OneOffMode.REPLACE_DAY else OneOffMode.ADDITIVE,
                            ),
                        )
                    }
                },
            ) { Text("Save") }
        },
    ) { padding ->
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete alarm?",
                text = "It will not ring.",
                confirmLabel = "Delete",
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    val target = editId
                    write("Could not delete the alarm.") { container.repository.deleteOneOff(target) }
                },
            )
        }
        if (!loaded) return@ScreenScaffold
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimeButton(time, onChange = { time = it }, modifier = Modifier.fillMaxWidth())
            QuickDates(today, date) { date = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Date", Modifier.weight(1f))
                DateButton(date, onChange = { date = it })
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Replace this day's recurring alarms")
                    Text(
                        if (replaceDay) "Policy alarms on this day won't ring." else "Policy alarms on this day ring as well.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = replaceDay, onCheckedChange = { replaceDay = it })
            }
            when {
                sameDay.isEmpty() -> Unit
                replacedByOther && !replaceDay -> Text(
                    "Another alarm on this day already replaces its recurring alarms, so they won't ring.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Text(if (replaceDay) "Will be replaced:" else "Will also ring:", style = MaterialTheme.typography.titleSmall)
                    sameDay.forEach { Text("${TimeText.time(it.time)}  ${it.label}") }
                }
            }
            if (inPast) {
                Text("This time is in the past.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QuickDates(today: LocalDate, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0L..7L).map { today.plusDays(it) }.forEach { day ->
            FilterChip(selected = day == selected, onClick = { onPick(day) }, label = { Text(TimeText.dayHeader(day, today)) })
        }
    }
}
