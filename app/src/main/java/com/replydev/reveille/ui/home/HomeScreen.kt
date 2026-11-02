package com.replydev.reveille.ui.home

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Occurrence
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.engine.Timeline
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import com.replydev.reveille.ui.components.CHECKS_FAILED
import com.replydev.reveille.ui.components.ScreenSession
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.animatedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

private const val TIMELINE_DAYS = 14

/** A schedule/armed mismatch must last this long before it is shown: a save re-arms right after the schedule changes. */
private const val MISMATCH_GRACE_MS = 3_000L

/** Wraps the armed alarm so "not loaded yet" (null) differs from "nothing armed". */
private class ArmedState(val armed: Armed?)


@Composable
fun HomeScreen(
    failingChecks: Int?,
    onOpenReliability: () -> Unit,
    onNewOneOff: () -> Unit,
    onNewPolicy: () -> Unit,
    onEditOneOff: (Long) -> Unit,
    onOpenPolicy: (Long) -> Unit,
) {
    val container = LocalContext.current.container
    val repository = container.repository
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val snapshot by repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val armedFlow = remember(container) { container.schedulerState.armedFlow.map(::ArmedState) }
    val armedState by armedFlow.collectAsStateWithLifecycle(initialValue = null)
    val ringing by RingingService.ringing.collectAsStateWithLifecycle()
    // Restarted (and so refreshed at once) whenever the armed alarm or the schedule changes: deciding the next alarm
    // with a stale clock would flag an alarm that just rang as "not set" until the next tick.
    val now by produceState(Instant.now(), armedState, snapshot) {
        value = Instant.now()
        while (true) {
            delay(15_000)
            value = Instant.now()
        }
    }
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val entries = remember(snapshot, now) { snapshot?.let { Timeline.build(it, now, zone, TIMELINE_DAYS) } ?: emptyList() }

    TabScaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = onNewPolicy,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                ) { Text("New policy") }
                ExtendedFloatingActionButton(onClick = onNewOneOff) { Text("New alarm") }
            }
        },
        snackbarHostState = snackbar,
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
        TabHeader("Today")
        LazyColumn(
            contentPadding = listPadding(PaddingValues(bottom = padding.calculateBottomPadding()), fabRows = 2),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
                        failingChecks?.takeIf { it != 0 }?.let { failing ->
                item(key = "banner") { ReliabilityBanner(failing, onOpenReliability, Modifier.animatedItem(this)) }
            }
            val loadedSnapshot = snapshot
            val loadedArmed = armedState
            if (loadedSnapshot != null && loadedArmed != null) {
                val state = nextUp(loadedArmed.armed, entries.firstOrNull { !it.skipped }?.occurrence, now)
                item(key = "next") { NextUpCard(state, ringing?.armed?.key, now, zone, Modifier.animatedItem(this)) }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text("No alarms in the next $TIMELINE_DAYS days. Add a policy or a one-off alarm.", Modifier.animatedItem(this).padding(top = 16.dp))
                    }
                }
            }
            entries.groupBy { it.occurrence.at.toLocalDate() }.forEach { (date, dayEntries) ->
                item(key = "header-$date") {
                    Text(
                        TimeText.dayHeader(date, today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.animatedItem(this).padding(top = 16.dp),
                    )
                }
                items(dayEntries, key = ::entryKey) { entry ->
                    OccurrenceRow(
                        entry = entry,
                        enabled = !session.busy,
                        onToggleSkip = { toggleSkip(session, repository, entry) },
                        onEditOneOff = onEditOneOff,
                        modifier = Modifier.animatedItem(this),
                    )
                }
            }
        }
        }
    }
}

/** A skipped entry can share its occurrence key with a real one, so the kind and instant are part of the key. */
private fun entryKey(entry: Timeline.Entry): String =
    "${if (entry.skipped) "s" else "r"}-${entry.occurrence.key}-${entry.occurrence.at.toInstant().toEpochMilli()}"

@Composable
private fun ReliabilityBanner(failing: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        val text = when (failing) {
            CHECKS_FAILED -> "Reliability checks could not run. Alarms may not ring. Tap to review."
            1 -> "1 reliability check needs attention. Alarms may not ring. Tap to fix."
            else -> "$failing reliability checks need attention. Alarms may not ring. Tap to fix."
        }
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

/** True once [mismatch] has held for [MISMATCH_GRACE_MS]; hides the normal gap between a save and its re-arm. */
@Composable
private fun persistent(mismatch: Any?): Boolean {
    val shown by produceState(false, mismatch) {
        value = false
        if (mismatch != null) {
            delay(MISMATCH_GRACE_MS)
            value = true
        }
    }
    return shown
}

@Composable
private fun NextUpCard(state: NextUp, ringingKey: String?, now: Instant, zone: ZoneId, modifier: Modifier = Modifier) {
    val unarmed: Occurrence? = when (state) {
        is NextUp.Set -> state.earlierUnarmed
        is NextUp.NotSet -> state.scheduled
        else -> null
    }
    val warn = persistent(unarmed?.let { it.key to it.at })
    Card(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp)) {
            when (state) {
                NextUp.None -> {
                    Text("Next alarm", style = MaterialTheme.typography.labelLarge)
                    Text("None scheduled", style = MaterialTheme.typography.headlineMedium)
                }
                is NextUp.Overdue -> {
                    val armed = state.armed
                    val isRinging = ringingKey == armed.key
                    Text(if (isRinging) "Ringing now" else "Overdue", style = MaterialTheme.typography.labelLarge)
                    Text(TimeText.dayAndTime(armed.at, now, zone), style = MaterialTheme.typography.headlineMedium)
                    Text(armedLabel(armed), style = MaterialTheme.typography.bodyMedium)
                    if (!isRinging) {
                        Warning("Due at ${TimeText.time(armed.at.atZone(zone).toLocalTime())} and should ring any moment. If it does not, check Reliability.")
                    }
                }
                is NextUp.Set -> {
                    val armed = state.armed
                    Text("Next alarm", style = MaterialTheme.typography.labelLarge)
                    Text(TimeText.dayAndTime(armed.at, now, zone), style = MaterialTheme.typography.headlineMedium)
                    Text("${armedLabel(armed)} · ${TimeText.relative(armed.at, now)}", style = MaterialTheme.typography.bodyMedium)
                    val earlier = state.earlierUnarmed
                    if (earlier != null && warn) {
                        Warning(
                            "Not set with the system: ${TimeText.dayAndTime(earlier.at.toInstant(), now, zone)} ${earlier.label} " +
                                "is scheduled earlier. Check Reliability.",
                        )
                    }
                }
                is NextUp.NotSet -> {
                    val at = state.scheduled.at.toInstant()
                    Text("Next alarm", style = MaterialTheme.typography.labelLarge)
                    Text(TimeText.dayAndTime(at, now, zone), style = MaterialTheme.typography.headlineMedium)
                    Text("${state.scheduled.label} · ${TimeText.relative(at, now)}", style = MaterialTheme.typography.bodyMedium)
                    Warning(if (warn) "Not set with the system, so it will not ring. Check Reliability." else "Setting the alarm…")
                }
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun armedLabel(armed: Armed): String = if (armed.isSnooze) "Snoozed: ${armed.label}" else armed.label

@Composable
private fun OccurrenceRow(
    entry: Timeline.Entry,
    enabled: Boolean,
    onToggleSkip: () -> Unit,
    onEditOneOff: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val occurrence = entry.occurrence
    val oneOff = occurrence.sources.filterIsInstance<Source.OneOffAlarm>().firstOrNull()
    val alpha = if (entry.skipped) 0.4f else 1f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(TimeText.time(occurrence.at.toLocalTime()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.alpha(alpha))
        Spacer(Modifier.width(16.dp))
        Text(
            if (entry.skipped) "${occurrence.label} (skipped)" else occurrence.label,
            modifier = Modifier.weight(1f).alpha(alpha),
        )
        if (oneOff != null) {
            TextButton(onClick = { onEditOneOff(oneOff.oneOffId) }) { Text("Edit") }
        }
        TextButton(enabled = enabled, onClick = onToggleSkip) { Text(if (entry.skipped) "Unskip" else "Skip") }
    }
}

/**
 * Skip skips the policy alarms of the occurrence (for their whole date) and deletes its one-offs; Undo re-saves the
 * one-offs and removes the skips. Unskip (skipped entries are policy alarms only) removes the skips.
 */
private fun toggleSkip(session: ScreenSession, repository: ScheduleRepository, entry: Timeline.Entry) {
    val policies = entry.occurrence.sources.filterIsInstance<Source.PolicyRule>()
    if (entry.skipped) {
        session.write("Could not unskip the alarm.", action = { policies.forEach { repository.unskip(it.policyId, it.date) } })
        return
    }
    val oneOffIds = entry.occurrence.sources.filterIsInstance<Source.OneOffAlarm>().map { it.oneOffId }
    val time = TimeText.time(entry.occurrence.at.toLocalTime())
    session.write(
        "Could not skip the alarm.",
        action = {
            val removed: List<OneOffEntity> = oneOffIds.mapNotNull { repository.oneOff(it) }
            repository.skipOccurrence(entry.occurrence.sources.map { it.key })
            removed to policiesRingingAgain(repository.snapshot(), removed.map { it.toModel() })
        },
        onSuccess = { (removed, ringingAgain) ->
            session.message(
                UiMessage(skipMessage(time, entry.occurrence.label, ringingAgain), actionLabel = "Undo") {
                    session.write(
                        "Could not undo the skip.",
                        guarded = false,
                        action = {
                            // A one-off whose time passed meanwhile stays deleted: restoring it would ring it at once.
                            removed.filter { LocalDateTime.of(it.date, it.time).isAfter(LocalDateTime.now()) }.forEach { repository.saveOneOff(it) }
                            policies.forEach { repository.unskip(it.policyId, it.date) }
                        },
                    )
                },
            )
        },
    )
}
