package com.replydev.reveille.ui.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.replydev.reveille.AppContainer
import com.replydev.reveille.container
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.engine.Timeline
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.components.runReporting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

private const val TIMELINE_DAYS = 14

@Composable
fun HomeScreen(
    onOpenPolicies: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReliability: () -> Unit,
    onNewOneOff: () -> Unit,
    onEditOneOff: (Long) -> Unit,
) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val snapshot by container.repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val armed by container.schedulerState.armedFlow.collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    var failingChecks by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            failingChecks = try {
                container.reliability.checks().count { !it.ok }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Reveille", "Reliability check failed", e)
                0
            }
        }
    }
    val zone = ZoneId.systemDefault()
    // atZone, not LocalDate.ofInstant: the latter is API 34.
    val today = now.atZone(zone).toLocalDate()
    val entries = remember(snapshot, now) { snapshot?.let { Timeline.build(it, now, zone, TIMELINE_DAYS) } ?: emptyList() }

    ScreenScaffold(
        title = "Reveille",
        actions = {
            TextButton(onClick = onOpenPolicies) { Text("Policies") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNewOneOff) { Text("New alarm") } },
        snackbarHostState = snackbar,
    ) { padding ->
        LazyColumn(
            contentPadding = listPadding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (failingChecks > 0) {
                item(key = "banner") { ReliabilityBanner(failingChecks, onOpenReliability) }
            }
            item(key = "next") { NextUpCard(armed, entries.firstOrNull { !it.skipped }, now, zone) }
            if (snapshot != null && entries.isEmpty()) {
                item(key = "empty") { Text("No alarms in the next $TIMELINE_DAYS days. Add a policy or a one-off alarm.", Modifier.padding(top = 16.dp)) }
            }
            entries.groupBy { it.occurrence.at.toLocalDate() }.forEach { (date, dayEntries) ->
                item(key = "header-$date") {
                    Text(
                        TimeText.dayHeader(date, today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(dayEntries, key = ::entryKey) { entry ->
                    OccurrenceRow(
                        entry = entry,
                        onToggleSkip = { scope.launch { snackbar.runReporting("Could not update the alarm.") { toggleSkip(container, entry) } } },
                        onEditOneOff = onEditOneOff,
                    )
                }
            }
        }
    }
}

/** A skipped entry can share its occurrence key with a real one, so the kind and instant are part of the key. */
private fun entryKey(entry: Timeline.Entry): String =
    "${if (entry.skipped) "s" else "r"}-${entry.occurrence.key}-${entry.occurrence.at.toInstant().toEpochMilli()}"

@Composable
private fun ReliabilityBanner(failing: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "$failing reliability ${if (failing == 1) "check needs" else "checks need"} attention. Alarms may not ring. Tap to fix.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Shows the alarm actually registered with AlarmManager (the same one the widget shows, including snoozes). If
 * nothing is armed while the schedule has an upcoming alarm, says so instead of pretending it will ring.
 */
@Composable
private fun NextUpCard(armed: Armed?, scheduled: Timeline.Entry?, now: Instant, zone: ZoneId) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Next alarm", style = MaterialTheme.typography.labelLarge)
            when {
                armed != null -> {
                    Text(TimeText.dayAndTime(armed.at, now, zone), style = MaterialTheme.typography.headlineMedium)
                    val label = if (armed.isSnooze) "Snoozed: ${armed.label}" else armed.label
                    Text("$label · ${TimeText.relative(armed.at, now)}", style = MaterialTheme.typography.bodyMedium)
                }
                scheduled != null -> {
                    val at = scheduled.occurrence.at.toInstant()
                    Text(TimeText.dayAndTime(at, now, zone), style = MaterialTheme.typography.headlineMedium)
                    Text("${scheduled.occurrence.label} · ${TimeText.relative(at, now)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Not set with the system yet. Check Reliability if this does not go away.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Text("None scheduled", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
private fun OccurrenceRow(entry: Timeline.Entry, onToggleSkip: () -> Unit, onEditOneOff: (Long) -> Unit) {
    val occurrence = entry.occurrence
    val oneOff = occurrence.sources.filterIsInstance<Source.OneOffAlarm>().firstOrNull()
    val hasPolicy = occurrence.sources.any { it is Source.PolicyRule }
    val alpha = if (entry.skipped) 0.4f else 1f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(TimeText.time(occurrence.at.toLocalTime()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.alpha(alpha))
        Spacer(Modifier.width(16.dp))
        Text(
            if (entry.skipped) "${occurrence.label} (skipped)" else occurrence.label,
            modifier = Modifier.weight(1f).alpha(alpha),
        )
        if (oneOff != null) {
            TextButton(onClick = { onEditOneOff(oneOff.oneOffId) }) { Text("Edit") }
        }
        if (hasPolicy) {
            TextButton(onClick = onToggleSkip) { Text(if (entry.skipped) "Unskip" else "Skip") }
        }
    }
}

/** Skip acts on the policy alarms only: a one-off merged into the same minute keeps ringing and is edited separately. */
private suspend fun toggleSkip(container: AppContainer, entry: Timeline.Entry) {
    val sources = entry.occurrence.sources.filterIsInstance<Source.PolicyRule>()
    if (entry.skipped) {
        sources.forEach { container.repository.unskip(it.policyId, it.date) }
    } else {
        container.repository.skipOccurrence(sources.map { it.key })
    }
}
