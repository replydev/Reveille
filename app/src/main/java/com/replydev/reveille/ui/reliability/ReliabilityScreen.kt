package com.replydev.reveille.ui.reliability

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.replydev.reveille.reliability.ReliabilityCheck
import com.replydev.reveille.reliability.appDetailsIntent
import com.replydev.reveille.text.Labels
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
import com.replydev.reveille.ui.text.rememberLabels
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
    val labels = rememberLabels()
    val res = labels.res
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
        session.write(res.getString(R.string.health_save_failed), guarded = false, action = { container.settings.setConfirmed(check.id, done) })
    }
    val failing = checks.filter { !it.ok }
    val passing = checks.filter { it.ok }

    unconfirm?.let { id ->
        val check = checks.firstOrNull { it.id == id }
        if (check == null) {
            unconfirm = null
        } else {
            ConfirmDialog(
                title = stringResource(R.string.health_unconfirm_title),
                text = stringResource(R.string.health_unconfirm_text, stringResource(check.title)),
                confirmLabel = stringResource(R.string.health_mark_not_done),
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
                TabHeader(stringResource(R.string.health_title), bottom = 4.dp) {
                    if (checks.isNotEmpty()) SummaryBar(res, passing.size, checks.size, Modifier.padding(top = 14.dp))
                }
            }
            items(failing, key = { "failing-${it.id}" }) { check ->
                FailingCard(
                    check = check,
                    onFix = {
                        if (!openFix(context, check.fix)) {
                            session.message(UiMessage(res.getString(R.string.health_open_failed)))
                        }
                    },
                    onConfirm = { setConfirmed(check, true) },
                    modifier = Modifier.animatedItem(this).padding(horizontal = 16.dp),
                )
            }
            if (passing.isNotEmpty()) {
                item(key = "passing") {
                    PassingCard(res, passing, onUnconfirm = { unconfirm = it.id }, Modifier.animatedItem(this).padding(horizontal = 16.dp))
                }
            }
            item(key = "test") {
                ReveilleCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.health_test_title), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                            Text(stringResource(R.string.health_test_detail), style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
                        }
                        PillButton(
                            stringResource(R.string.health_test_start),
                            onClick = {
                                session.write(
                                    res.getString(R.string.health_test_failed),
                                    action = { scheduleTestAlarm(container, labels) },
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
                    stringResource(R.string.health_force_stop),
                    Modifier.padding(horizontal = 20.dp),
                    style = ReveilleTheme.type.caption,
                    color = ReveilleTheme.colors.muted,
                )
            }
            item(key = "log-title") { Overline(stringResource(R.string.health_recent_alarms), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) }
            if (log.isEmpty()) {
                item(key = "log-empty") {
                    Text(stringResource(R.string.health_log_empty), Modifier.animatedItem(this).padding(horizontal = 20.dp), style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
                }
            }
            if (log.isNotEmpty()) {
                item(key = "log") {
                    Column(Modifier.animatedItem(this).animateContentSize(Motion.size).padding(horizontal = 16.dp)) {
                        log.forEach { LogRow(it, logTime(labels, it.scheduledAt, today, ZoneId.systemDefault()), outcomeText(res, it.outcome)) }
                    }
                }
            }
        }
    }
}

/** "X of Y checks pass", the verdict, and one segment per check. */
@Composable
private fun SummaryBar(res: Resources, passing: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val summary = healthSummary(res, passing, total)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The verdict moves under the count when both don't fit on one line, so neither breaks mid-word.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.Bottom) {
            Text(summary.text, Modifier.padding(end = 12.dp), style = ReveilleTheme.type.titleS.copy(fontWeight = FontWeight.Medium))
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
                Text(stringResource(check.title), style = ReveilleTheme.type.cardTitle)
                Text(stringResource(check.detail), style = ReveilleTheme.type.paragraphSmall, color = colors.dangerOnContainerMuted)
            }
        }
        if (check.fix != null || check.manual) {
            // "Mark as done" moves under the button when both don't fit on one line.
            FlowRow(
                Modifier.padding(start = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (check.fix != null) {
                    PillButton(
                        stringResource(R.string.health_open_settings), onFix,
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
                        Text(stringResource(R.string.health_mark_done), style = ReveilleTheme.type.body, color = colors.onBg)
                    }
                }
            }
        }
    }
}

/** Every passing check in one card; tapping a confirmed manual check offers to un-confirm it. */
@Composable
private fun PassingCard(res: Resources, checks: List<ReliabilityCheck>, onUnconfirm: (ReliabilityCheck) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val unconfirmLabel = stringResource(R.string.health_mark_not_done)
    ReveilleCard(modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
        checks.forEach { check ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .then(if (check.manual) Modifier.clickable(onClickLabel = unconfirmLabel) { onUnconfirm(check) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReveilleIcon(R.drawable.ic_check, null, tint = colors.success, size = 18.dp)
                Text(stringResource(check.title), Modifier.weight(1f), style = ReveilleTheme.type.body)
                passingNote(res, check.id, check.manual)?.let { Text(it, style = ReveilleTheme.type.small, color = colors.muted) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: FireLogEntity, time: String, outcome: String, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Wide enough for "Fri, 30 Oct 08:55" in most locales, so every label starts in the same column; a longer
        // date pushes its label along rather than being cut off.
        Text(time, Modifier.widthIn(min = 128.dp), style = ReveilleTheme.type.mono, color = colors.muted, maxLines = 1)
        Text(entry.label, Modifier.weight(1f), style = ReveilleTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            outcome,
            style = ReveilleTheme.type.caption,
            color = if (entry.outcome == FireOutcome.MISSED) colors.dangerText else colors.muted,
        )
    }
}

/**
 * Saves an additive one-off 1–2 minutes ahead (on a whole minute) and says whether the system actually armed it,
 * so a refused exact alarm is not presented as a working test.
 */
internal suspend fun scheduleTestAlarm(container: AppContainer, labels: Labels): String {
    val zone = ZoneId.systemDefault()
    val at = LocalDateTime.now().plusMinutes(2).truncatedTo(ChronoUnit.MINUTES)
    val id = container.repository.saveOneOff(
        OneOffEntity(date = at.toLocalDate(), time = at.toLocalTime(), label = labels.res.getString(R.string.health_test_label), mode = OneOffMode.ADDITIVE),
    )
    val armed = container.schedulerState.armed()
    val result = testAlarmResult(armed, Source.OneOffAlarm(id).key, at.atZone(zone).toInstant(), Instant.now())
    val earlier = armed?.let { labels.formats.time(it.at.atZone(zone).toLocalTime()) }
    return testAlarmMessage(labels.res, result, labels.formats.time(at.toLocalTime()), earlier)
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

