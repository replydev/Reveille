package com.replydev.reveille.ui.reliability

import androidx.compose.foundation.layout.PaddingValues
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.replydev.reveille.AppContainer
import com.replydev.reveille.container
import com.replydev.reveille.data.FireLogEntity
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.reliability.ReliabilityCheck
import com.replydev.reveille.reliability.appDetailsIntent
import com.replydev.reveille.ui.components.ScreenScaffold
import com.replydev.reveille.ui.components.SectionTitle
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.listPadding
import com.replydev.reveille.ui.theme.animatedItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Reveille"

@Composable
fun ReliabilityScreen() {
    val context = LocalContext.current
    val container = context.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    var checks by remember { mutableStateOf<List<ReliabilityCheck>>(emptyList()) }
    val confirmed by container.settings.confirmedChecks.collectAsStateWithLifecycle(initialValue = emptySet())
    val log by container.database.fireLogDao().observeRecent(20).collectAsStateWithLifecycle(initialValue = emptyList())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, confirmed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                checks = container.reliability.checks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reliability check failed", e)
            }
        }
    }

    TabScaffold(snackbarHostState = snackbar) { padding ->
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = listPadding(PaddingValues(bottom = padding.calculateBottomPadding())),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { TabHeader("Health") }
            item(key = "intro") {
                Text(
                    "Reveille uses the same alarm API as the stock Clock app and sets a backup 2 minutes after every alarm. " +
                        "Phone makers can still block apps: make every item below green, then ring a test alarm with the screen off.",
                )
            }
            items(checks, key = { "check-${it.id}" }) { check ->
                CheckCard(
                    check = check,
                    onFix = {
                        if (!openFix(context, check.fix)) {
                            session.message(UiMessage("Could not open the settings screen. Open it from the phone's Settings app."))
                        }
                    },
                    onConfirm = { done ->
                        session.write("Could not save.", guarded = false, action = { container.settings.setConfirmed(check.id, done) })
                    },
                )
            }
            item(key = "test") {
                Button(
                    enabled = !session.busy,
                    onClick = {
                        session.write(
                            "Could not set the test alarm.",
                            action = { scheduleTestAlarm(container) },
                            onSuccess = { session.message(UiMessage(it)) },
                        )
                    },
                ) { Text("Ring a test alarm in 2 minutes") }
            }
            item(key = "force-stop") {
                Text(
                    "If Reveille is force-stopped, Android deletes all its alarms, and restarting the phone does not bring them " +
                        "back. They are set again the next time you open Reveille (tapping its widget works too), and you are warned " +
                        "about any alarm that was missed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item(key = "log-title") { SectionTitle("Recent alarms") }
            if (log.isEmpty()) item(key = "log-empty") { Text("Nothing yet.", Modifier.animatedItem(this)) }
            items(log, key = { "log-${it.id}" }) { entry ->
                Text(logLine(entry), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.animatedItem(this))
            }
        }
    }
}

@Composable
private fun CheckCard(check: ReliabilityCheck, onFix: () -> Unit, onConfirm: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (check.ok) "✓" else "✗",
                    color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.width(8.dp))
                Text(check.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(check.detail, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (check.fix != null) OutlinedButton(onClick = onFix) { Text("Open settings") }
                if (check.manual) {
                    Spacer(Modifier.width(8.dp))
                    Checkbox(checked = check.ok, onCheckedChange = onConfirm)
                    Text("Done")
                }
            }
        }
    }
}

/**
 * Saves an additive one-off 1–2 minutes ahead (on a whole minute) and says whether the system actually armed it,
 * so a refused exact alarm is not presented as a working test.
 */
private suspend fun scheduleTestAlarm(container: AppContainer): String {
    val zone = ZoneId.systemDefault()
    val at = LocalDateTime.now().plusMinutes(2).truncatedTo(ChronoUnit.MINUTES)
    val id = container.repository.saveOneOff(
        OneOffEntity(date = at.toLocalDate(), time = at.toLocalTime(), label = "Test alarm", mode = OneOffMode.ADDITIVE),
    )
    val armed = container.schedulerState.armed()
    val time = TimeText.time(at.toLocalTime())
    return when (testAlarmResult(armed, Source.OneOffAlarm(id).key, at.atZone(zone).toInstant(), Instant.now())) {
        TestAlarmResult.SET -> "Test alarm set for $time. Lock your phone now."
        TestAlarmResult.AFTER_ANOTHER ->
            "Test alarm saved for $time; it is set once the alarm at ${TimeText.time(armed!!.at.atZone(zone).toLocalTime())} has rung."
        TestAlarmResult.NOT_CONFIRMED ->
            "Test alarm saved for $time, but the system did not confirm it. Fix the items above."
    }
}

/**
 * Opens an OEM or system settings screen; falls back to the app details page if it is missing or refused
 * (HyperOS deep links can throw SecurityException). Returns false if neither could be opened.
 */
private fun openFix(context: Context, intent: Intent?): Boolean {
    if (intent == null) return true
    return try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Settings screen not available, opening app details", e)
        try {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e2: Exception) {
            Log.w(TAG, "App details not available", e2)
            false
        }
    }
}

private fun logLine(entry: FireLogEntity): String {
    val at = Instant.ofEpochMilli(entry.scheduledAt).atZone(ZoneId.systemDefault())
    return "${TimeText.dayHeader(at.toLocalDate(), LocalDate.now())} ${TimeText.time(at.toLocalTime())}  ${entry.label} — ${outcomeText(entry.outcome)}"
}

/** FIRED means rung but never dismissed or snoozed; every other value reads fine as its own name. */
private fun outcomeText(outcome: FireOutcome): String = when (outcome) {
    FireOutcome.FIRED -> "rang, not answered yet"
    else -> outcome.name.lowercase().replace('_', ' ')
}
