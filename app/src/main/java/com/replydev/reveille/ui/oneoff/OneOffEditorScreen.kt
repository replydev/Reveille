package com.replydev.reveille.ui.oneoff

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.replydev.reveille.ui.components.LeaveWhenSaved
import com.replydev.reveille.ui.components.SheetScaffold
import com.replydev.reveille.ui.components.TimeButton
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.theme.Motion
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A one-off must be after this moment: anything earlier would ring the moment it is saved. */
private fun isInPast(date: LocalDate, time: LocalTime, now: LocalDateTime = LocalDateTime.now()): Boolean =
    !LocalDateTime.of(date, time).isAfter(now)

@Composable
fun OneOffEditorScreen(onDone: () -> Unit) {
    val container = LocalContext.current.container
    val repository = container.repository
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    LeaveWhenSaved(session, onDone)

    // Follows the clock (and midnight) while the screen is open: the past-time check and the quick-date chips.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(5_000)
            value = LocalDateTime.now()
        }
    }
    val today = now.toLocalDate()

    // The draft survives rotation, theme changes and process death; it is loaded from the database only once.
    var loaded by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1)) }
    var time by rememberSaveable { mutableStateOf(LocalTime.of(7, 0)) }
    var label by rememberSaveable { mutableStateOf("") }
    var replaceDay by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val id = session.editId
        val existing = if (id == 0L) null else repository.oneOff(id)
        if (existing != null) {
            date = existing.date
            time = existing.time
            label = existing.label.orEmpty()
            replaceDay = existing.mode == OneOffMode.REPLACE_DAY
        } else if (id != 0L) {
            // Deleted since the link was opened (by the user, skipped from the widget, or cleaned up after its day
            // passed — ringing does not delete it): save as a new alarm, never resurrect the id.
            session.editId = 0L
            session.message(UiMessage("This alarm no longer exists. Saving creates a new one."))
        }
        loaded = true
    }

    val editId = session.editId
    val snapshot by container.repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val sameDay = remember(snapshot, date) { snapshot?.let { ScheduleEngine.policyAlarmsOn(it, date) } ?: emptyList() }
    val replacedByOther = snapshot?.oneOffs?.any { it.date == date && it.mode == OneOffMode.REPLACE_DAY && it.id != editId } == true
    val inPast = isInPast(date, time, now)

    SheetScaffold(
        title = if (editId == 0L) "One-off alarm" else "Edit alarm",
        onClose = onDone,
        snackbarHostState = snackbar,
        actions = {
            if (editId != 0L) {
                TextButton(enabled = loaded && !session.busy, onClick = { confirmDelete = true }) { Text("Delete") }
            }
            TextButton(
                enabled = loaded && !session.busy && !inPast,
                onClick = {
                    if (isInPast(date, time)) {
                        session.message(UiMessage("This time is in the past."))
                        return@TextButton
                    }
                    val entity = OneOffEntity(
                        id = editId,
                        date = date,
                        time = time,
                        label = label.trim().ifEmpty { null },
                        mode = if (replaceDay) OneOffMode.REPLACE_DAY else OneOffMode.ADDITIVE,
                    )
                    session.write(
                        "Could not save the alarm.",
                        action = { repository.saveOneOff(entity) },
                        onSuccess = { savedId ->
                            // Set before anything else: if the screen stays open, saving again updates this alarm.
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
                title = "Delete alarm?",
                text = "It will not ring. If it is already snoozed, the snooze still rings.",
                confirmLabel = "Delete",
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    val target = editId
                    session.write(
                        "Could not delete the alarm.",
                        action = { repository.deleteOneOff(target) },
                        onSuccess = { session.saved = true },
                    )
                },
            )
        }
        if (!loaded) return@SheetScaffold
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
            AnimatedVisibility(sameDay.isNotEmpty(), enter = Motion.expand, exit = Motion.collapse) {
                Column(Modifier.animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (replacedByOther && !replaceDay) {
                        Text(
                            "Another alarm on this day already replaces its recurring alarms, so they won't ring.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(if (replaceDay) "Will be replaced:" else "Will also ring:", style = MaterialTheme.typography.titleSmall)
                        sameDay.forEach { Text("${TimeText.time(it.time)}  ${it.label}") }
                    }
                }
            }
            AnimatedVisibility(inPast, enter = Motion.expand, exit = Motion.collapse) {
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
