package com.replydev.reveille.ui.reliability

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.replydev.reveille.AppContainer
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.FireLogEntity
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.reliability.ReliabilityCheck
import com.replydev.reveille.reliability.appDetailsIntent
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PillButton
import com.replydev.reveille.ui.components.PillStyle
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.rememberToday
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.animatedItem
import java.time.Instant
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
    val today = rememberToday()
    var unconfirm by rememberSaveable { mutableStateOf<String?>(null) }
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
    val setConfirmed: (ReliabilityCheck, Boolean) -> Unit = { check, done ->
        session.write("Could not save.", guarded = false, action = { container.settings.setConfirmed(check.id, done) })
    }
    val failing = checks.filter { !it.ok }
    val passing = checks.filter { it.ok }

    unconfirm?.let { id ->
        val check = checks.firstOrNull { it.id == id }
        if (check == null) {
            unconfirm = null
        } else {
            ConfirmDialog(
                title = "Mark as not done?",
                text = "\"${check.title}\" will be listed as needing attention again.",
                confirmLabel = "Mark as not done",
                onDismiss = { unconfirm = null },
                onConfirm = {
                    unconfirm = null
                    setConfirmed(check, false)
                },
            )
        }
    }

    TabScaffold(snackbarHostState = snackbar) { padding ->
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                TabHeader("Health", bottom = 4.dp) {
                    if (checks.isNotEmpty()) SummaryBar(passing.size, checks.size, Modifier.padding(top = 14.dp))
                }
            }
            items(failing, key = { "failing-${it.id}" }) { check ->
                FailingCard(
                    check = check,
                    onFix = {
                        if (!openFix(context, check.fix)) {
                            session.message(UiMessage("Could not open the settings screen. Open it from the phone's Settings app."))
                        }
                    },
                    onConfirm = { setConfirmed(check, true) },
                    modifier = Modifier.animatedItem(this).padding(horizontal = 16.dp),
                )
            }
            if (passing.isNotEmpty()) {
                item(key = "passing") {
                    PassingCard(passing, onUnconfirm = { unconfirm = it.id }, Modifier.animatedItem(this).padding(horizontal = 16.dp))
                }
            }
            item(key = "test") {
                ReveilleCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Ring a test alarm", style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                            Text("In 2 minutes. Lock the phone and wait.", style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
                        }
                        PillButton(
                            "Start",
                            onClick = {
                                session.write(
                                    "Could not set the test alarm.",
                                    action = { scheduleTestAlarm(container) },
                                    onSuccess = { session.message(UiMessage(it)) },
                                )
                            },
                            height = 44.dp,
                            enabled = !session.busy,
                            textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            item(key = "force-stop") {
                Text(
                    "If Reveille is force-stopped, Android deletes all its alarms, and restarting the phone does not bring them " +
                        "back. They are set again the next time you open Reveille (tapping its widget works too), and you are warned " +
                        "about any alarm that was missed.",
                    Modifier.padding(horizontal = 20.dp),
                    style = ReveilleTheme.type.caption,
                    color = ReveilleTheme.colors.muted,
                )
            }
            item(key = "log-title") { Overline("Recent alarms", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) }
            if (log.isEmpty()) {
                item(key = "log-empty") {
                    Text("Nothing yet.", Modifier.animatedItem(this).padding(horizontal = 20.dp), style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
                }
            }
            if (log.isNotEmpty()) {
                item(key = "log") {
                    Column(Modifier.animatedItem(this).animateContentSize(Motion.size).padding(horizontal = 16.dp)) {
                        log.forEach { LogRow(it, logTime(it.scheduledAt, today, ZoneId.systemDefault())) }
                    }
                }
            }
        }
    }
}

/** "X of Y checks pass", the verdict, and one segment per check. */
@Composable
private fun SummaryBar(passing: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val summary = healthSummary(passing, total)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(summary.text, Modifier.weight(1f), style = ReveilleTheme.type.titleS.copy(fontWeight = FontWeight.Medium))
            Text(summary.verdict, style = ReveilleTheme.type.caption, color = if (summary.allGood) colors.success else colors.dangerText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { index ->
                Box(Modifier.weight(1f).height(6.dp).background(if (index < passing) colors.success else colors.danger, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun FailingCard(check: ReliabilityCheck, onFix: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    ReveilleCard(modifier.fillMaxWidth(), color = colors.dangerContainer, border = colors.dangerLine, spacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(colors.dangerTile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                ReveilleIcon(R.drawable.ic_warning, null, tint = colors.dangerText, size = 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(check.title, style = ReveilleTheme.type.cardTitle)
                Text(check.detail, style = ReveilleTheme.type.paragraphSmall, color = colors.dangerOnContainerMuted)
            }
        }
        if (check.fix != null || check.manual) {
            Row(Modifier.padding(start = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (check.fix != null) {
                    PillButton(
                        "Open settings", onFix,
                        style = PillStyle.Danger,
                        height = 40.dp,
                        textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    )
                }
                if (check.manual) {
                    Row(
                        Modifier.heightIn(min = 40.dp).toggleable(value = false, role = Role.Checkbox) { onConfirm() }.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = null,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            colors = CheckboxDefaults.colors(uncheckedColor = colors.dangerText, checkedColor = colors.dangerText, checkmarkColor = colors.onDanger),
                        )
                        Text("Mark as done", style = ReveilleTheme.type.body, color = colors.onBg)
                    }
                }
            }
        }
    }
}

/** Every passing check in one card; tapping a confirmed manual check offers to un-confirm it. */
@Composable
private fun PassingCard(checks: List<ReliabilityCheck>, onUnconfirm: (ReliabilityCheck) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    ReveilleCard(modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
        checks.forEach { check ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .then(if (check.manual) Modifier.clickable(onClickLabel = "Mark as not done") { onUnconfirm(check) } else Modifier)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReveilleIcon(R.drawable.ic_check, null, tint = colors.success, size = 18.dp)
                Text(check.title, Modifier.weight(1f), style = ReveilleTheme.type.body)
                passingNote(check.id, check.manual)?.let { Text(it, style = ReveilleTheme.type.small, color = colors.muted) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: FireLogEntity, time: String, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Wide enough for "Fri 30 Oct 08:55", so every label starts in the same column.
        Text(time, Modifier.width(128.dp), style = ReveilleTheme.type.mono, color = colors.muted, maxLines = 1)
        Text(entry.label, Modifier.weight(1f), style = ReveilleTheme.type.caption, maxLines = 1)
        Text(
            outcomeText(entry.outcome),
            style = ReveilleTheme.type.caption,
            color = if (entry.outcome == FireOutcome.MISSED) colors.dangerText else colors.muted,
        )
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

