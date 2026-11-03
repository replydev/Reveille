package com.replydev.reveille.ui.home

import android.content.res.Resources
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.data.ScheduleRepository
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.Timeline
import com.replydev.reveille.text.Labels
import com.replydev.reveille.ui.components.Chip
import com.replydev.reveille.ui.components.ChipText
import com.replydev.reveille.ui.components.Dot
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PillButton
import com.replydev.reveille.ui.components.PillStyle
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleFab
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.ScreenSession
import com.replydev.reveille.ui.components.StatusPill
import com.replydev.reveille.ui.components.TabHeader
import com.replydev.reveille.ui.components.TabScaffold
import com.replydev.reveille.ui.components.TextAction
import com.replydev.reveille.ui.components.TimeDisplay
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.animatedItem
import com.replydev.reveille.ui.theme.sourceColor
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.sourceKeyColor
import com.replydev.reveille.ui.update.UpdateNotice
import com.replydev.reveille.update.GuardResult
import com.replydev.reveille.update.showUpdateNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
    onEditOneOff: (Long) -> Unit,
    onOpenPolicy: (Long) -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val container = LocalContext.current.container
    val labels = rememberLabels()
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
    val intervals = remember(snapshot) { snapshot?.ruleIntervals() ?: emptyMap() }
    val updates = container.updates
    val updatePrefs by updates.store.prefs.collectAsStateWithLifecycle(initialValue = null)
    val update = updatePrefs?.nudge(updates.installedVersionCode)
    // Health wins the user's attention, and nothing about updates shows within 30 minutes of an alarm.
    val updateGuard by produceState<GuardResult?>(null, update, armedState, ringing, now) {
        value = if (update == null) null else updates.guard(now)
    }

    TabScaffold(
        floatingActionButton = { ReveilleFab(stringResource(R.string.today_new_one_off), onClick = onNewOneOff) },
        snackbarHostState = snackbar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
        ) {
            item(key = "header") {
                TabHeader(
                    title = stringResource(R.string.today_title),
                    overline = labels.formats.headerDate(today),
                    trailing = failingChecks?.takeIf { it != 0 }?.let { failing -> { StatusPill(issuesLabel(labels.res, failing), onClick = onOpenReliability) } },
                )
            }
            val loadedSnapshot = snapshot
            val loadedArmed = armedState
            if (loadedSnapshot != null && loadedArmed != null) {
                val scheduled = entries.firstOrNull { !it.skipped }
                val state = nextUp(loadedArmed.armed, scheduled?.occurrence, now)
                item(key = "next") {
                    NextUpCard(
                        state = state,
                        ringingKey = ringing?.armed?.key,
                        now = now,
                        zone = zone,
                        labels = labels,
                        skippable = skippableEntry(state, entries),
                        skipEnabled = !session.busy,
                        onSkip = { toggleSkip(session, repository, labels, it) },
                        onOpen = { target ->
                            when (target) {
                                is HeroTarget.Policy -> onOpenPolicy(target.id)
                                is HeroTarget.OneOff -> onEditOneOff(target.id)
                            }
                        },
                        modifier = Modifier.animatedItem(this).padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                if (update != null && updateGuard?.let { showUpdateNotice(update, failingChecks, it) } == true) {
                    item(key = "update") {
                        UpdateNotice(
                            update,
                            onView = onOpenUpdate,
                            onDismiss = { container.appScope.launch { updates.store.dismiss(update.versionCode) } },
                            modifier = Modifier.animatedItem(this).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        )
                    }
                }
                item(key = "upcoming-title") {
                    Row(
                        Modifier.animatedItem(this).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(pluralStringResource(R.plurals.today_next_days, TIMELINE_DAYS, TIMELINE_DAYS), Modifier.weight(1f), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                        Text(alarmCount(labels.res, entries.count { !it.skipped }), style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            pluralStringResource(R.plurals.today_empty, TIMELINE_DAYS, TIMELINE_DAYS),
                            Modifier.animatedItem(this).padding(horizontal = 20.dp, vertical = 12.dp),
                            style = ReveilleTheme.type.body,
                            color = ReveilleTheme.colors.muted,
                        )
                    }
                }
            }
            entries.groupBy { it.occurrence.at.toLocalDate() }.forEach { (date, dayEntries) ->
                item(key = "day-$date") {
                    DayGroup(
                        date = date,
                        entries = dayEntries,
                        intervals = intervals,
                        labels = labels,
                        enabled = !session.busy,
                        onToggleSkip = { toggleSkip(session, repository, labels, it) },
                        onEditOneOff = onEditOneOff,
                        modifier = Modifier.animatedItem(this),
                    )
                }
            }
        }
    }
}

private fun ScheduleSnapshot.ruleIntervals(): Map<Long, Int> = policies.flatMap { it.rules }.associate { it.id to it.intervalWeeks }

/** The timeline entry the hero's "Skip this one" acts on: the occurrence it shows, if the timeline has it. */
private fun skippableEntry(state: NextUp, entries: List<Timeline.Entry>): Timeline.Entry? = when (state) {
    is NextUp.Set -> entries.firstOrNull { !it.skipped && it.occurrence.key == state.armed.key && it.occurrence.at.toInstant() == state.armed.at }
    is NextUp.NotSet -> entries.firstOrNull { !it.skipped && it.occurrence == state.scheduled }
    else -> null
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

/** What the hero shows, whatever the [NextUp] state. */
private class Hero(
    val overline: String,
    val at: Instant?,
    val label: String?,
    val color: Color,
    val backup: String?,
    val armedOk: Boolean,
    val warning: String?,
    /** The warning is the transient "Setting the alarm…", not a problem. */
    val settling: Boolean,
    val target: HeroTarget?,
)

@Composable
private fun NextUpCard(
    state: NextUp,
    ringingKey: String?,
    now: Instant,
    zone: ZoneId,
    labels: Labels,
    skippable: Timeline.Entry?,
    skipEnabled: Boolean,
    onSkip: (Timeline.Entry) -> Unit,
    onOpen: (HeroTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val unarmed = when (state) {
        is NextUp.Set -> state.earlierUnarmed
        is NextUp.NotSet -> state.scheduled
        else -> null
    }
    val warn = persistent(unarmed?.let { it.key to it.at })
    val res = labels.res
    val nextWakeUp = stringResource(R.string.today_next_wake_up)
    val hero = when (state) {
        NextUp.None -> Hero(nextWakeUp, null, null, colors.accent, null, armedOk = false, warning = null, settling = false, target = null)
        is NextUp.Overdue -> {
            val armed = state.armed
            val isRinging = ringingKey == armed.key
            Hero(
                res.getString(if (isRinging) R.string.today_ringing_now else R.string.today_overdue), armed.at, armedLabel(res, armed),
                sourceKeyColor(heroKeys(armed)), null,
                armedOk = false,
                warning = if (isRinging) null else res.getString(R.string.today_overdue_warning, labels.formats.time(armed.at.atZone(zone).toLocalTime())),
                settling = false,
                target = null,
            )
        }
        is NextUp.Set -> {
            val armed = state.armed
            val earlier = state.earlierUnarmed?.takeIf { warn }
            Hero(
                nextWakeUp, armed.at, armedLabel(res, armed), sourceKeyColor(heroKeys(armed)), backupText(labels, armed.at, zone),
                armedOk = earlier == null,
                warning = earlier?.let {
                    res.getString(R.string.today_not_set_earlier, labels.dayAndTime(it.at.toInstant(), now, zone), it.label)
                },
                settling = false,
                target = heroTarget(heroKeys(armed)),
            )
        }
        is NextUp.NotSet -> Hero(
            nextWakeUp, state.scheduled.at.toInstant(), state.scheduled.label, sourceColor(state.scheduled.sources), null,
            armedOk = false,
            warning = res.getString(if (warn) R.string.today_not_set else R.string.today_setting),
            settling = !warn,
            target = heroTarget(state.scheduled.sources.map { it.key }),
        )
    }
    ReveilleCard(modifier.fillMaxWidth(), radius = 28.dp, padding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp)) {
        Column(Modifier.animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(hero.overline, Modifier.weight(1f), color = colors.accent)
                if (hero.armedOk) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(colors.accent)
                        Text(stringResource(R.string.today_armed), style = type.small, color = colors.muted)
                    }
                }
            }
            val at = hero.at
            if (at == null) {
                Text(stringResource(R.string.today_none_scheduled), style = type.headline)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeDisplay(labels.formats.timeParts(at.atZone(zone).toLocalTime()), type.displayL, type.titleS, Modifier.alignByBaseline(), lineBox = 0.9f)
                    Column(Modifier.alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(labels.dayPart(at, now, zone), style = type.titleS.copy(fontWeight = FontWeight.Medium))
                        Text(labels.relative(at, now), style = type.body, color = colors.muted)
                    }
                }
            }
            if (hero.label != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip {
                        PolicyDot(hero.color)
                        ChipText(hero.label)
                    }
                    hero.backup?.let { Chip { ChipText(it, color = colors.muted, style = type.mono) } }
                }
            }
            hero.warning?.let { text ->
                val settling = hero.settling
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!settling) ReveilleIcon(R.drawable.ic_warning, null, Modifier.padding(top = 1.dp), tint = colors.dangerText, size = 16.dp)
                    Text(text, style = type.caption, color = if (settling) colors.muted else colors.dangerText)
                }
            }
            val target = hero.target
            if (skippable != null || target != null) {
                // Equal heights when one label wraps onto two lines.
                Row(Modifier.padding(top = 4.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (skippable != null) {
                        PillButton(
                            stringResource(R.string.today_skip_this_one), { onSkip(skippable) }, Modifier.weight(1f).fillMaxHeight(),
                            style = PillStyle.Outlined,
                            enabled = skipEnabled,
                        )
                    }
                    if (target != null) {
                        PillButton(
                            stringResource(if (target is HeroTarget.OneOff) R.string.common_edit else R.string.today_open_policy),
                            { onOpen(target) },
                            Modifier.weight(1f).fillMaxHeight(),
                            style = PillStyle.Secondary,
                        )
                    }
                }
            }
        }
    }
}

/** A snooze carries its alarm's sources (for the ringing theme), but the hero shows it as before: accent, no editor link. */
private fun heroKeys(armed: Armed): List<String> = if (armed.isSnooze) emptyList() else armed.sourceKeys

private fun armedLabel(res: Resources, armed: Armed): String =
    if (armed.isSnooze) res.getString(R.string.today_snoozed, armed.label) else armed.label

/** One date of the timeline: the weekday and day number on the left, its alarms on the right. */
@Composable
private fun DayGroup(
    date: LocalDate,
    entries: List<Timeline.Entry>,
    intervals: Map<Long, Int>,
    labels: Labels,
    enabled: Boolean,
    onToggleSkip: (Timeline.Entry) -> Unit,
    onEditOneOff: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReveilleTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .topDivider(colors.surfaceHigh)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(44.dp).padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(labels.formats.weekdayShort(date.dayOfWeek).uppercase(labels.formats.locale), style = ReveilleTheme.type.dayOverline, color = colors.muted)
            Text("${date.dayOfMonth}", style = ReveilleTheme.type.dayNumber)
        }
        Column(Modifier.weight(1f).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry ->
                OccurrenceRow(entry, intervals, labels, enabled, { onToggleSkip(entry) }, onEditOneOff)
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    entry: Timeline.Entry,
    intervals: Map<Long, Int>,
    labels: Labels,
    enabled: Boolean,
    onToggleSkip: () -> Unit,
    onEditOneOff: (Long) -> Unit,
) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val occurrence = entry.occurrence
    val oneOff = occurrence.sources.filterIsInstance<Source.OneOffAlarm>().firstOrNull()
    val faded = Modifier.alpha(if (entry.skipped) 0.45f else 1f)
    // "12:55 PM" doesn't fit the 24-hour column; a wider minimum keeps the labels aligned across rows.
    val timeWidth = if (labels.formats.is24Hour) 62.dp else 92.dp
    ActionRow(
        // The time, the dot and a few characters of the label.
        minContent = timeWidth + 12.dp + 16.dp + 64.dp,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labels.formats.time(occurrence.at.toLocalTime()),
                    faded.widthIn(min = timeWidth),
                    style = type.timeRow.copy(textDecoration = if (entry.skipped) TextDecoration.LineThrough else null),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(12.dp))
                Row(faded.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolicyDot(sourceColor(occurrence.sources))
                    LabelAndNote(occurrence.label, occurrenceNote(labels.res, occurrence.sources, entry.skipped, intervals::get), Modifier.weight(1f))
                }
            }
        },
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A one-off keeps both actions: Edit opens it, Skip deletes it with Undo.
                if (oneOff != null) TextAction(stringResource(R.string.common_edit), { onEditOneOff(oneOff.oneOffId) })
                TextAction(stringResource(if (entry.skipped) R.string.today_unskip else R.string.today_skip), onToggleSkip, enabled = enabled)
            }
        },
    )
}

/**
 * [content] and [actions] on one line, at least 40 dp tall. When the actions would leave [content] less than
 * [minContent] (long translations on a narrow phone), they move to a second line, aligned to the end.
 */
@Composable
private fun ActionRow(minContent: Dp, content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Layout(contents = listOf(content, actions), modifier = Modifier.fillMaxWidth()) { (contentSlot, actionsSlot), constraints ->
        val width = constraints.maxWidth
        val minHeight = 40.dp.roundToPx()
        val actionsPlaceable = actionsSlot.single().measure(Constraints(maxWidth = width))
        val oneLine = width - actionsPlaceable.width >= minContent.roundToPx()
        val contentWidth = if (oneLine) width - actionsPlaceable.width else width
        val contentPlaceable = contentSlot.single().measure(Constraints.fixedWidth(contentWidth))
        val lineHeight = maxOf(minHeight, contentPlaceable.height, if (oneLine) actionsPlaceable.height else 0)
        val height = if (oneLine) lineHeight else lineHeight + actionsPlaceable.height
        layout(width, height) {
            contentPlaceable.place(0, (lineHeight - contentPlaceable.height) / 2)
            if (oneLine) {
                actionsPlaceable.place(width - actionsPlaceable.width, (lineHeight - actionsPlaceable.height) / 2)
            } else {
                actionsPlaceable.place(width - actionsPlaceable.width, lineHeight)
            }
        }
    }
}

/**
 * The occurrence label and its muted [note] on one line. When both don't fit, the label keeps its full width up to
 * half the line (so a long note can't squeeze it to "…") and both are ellipsized.
 */
@Composable
private fun LabelAndNote(label: String, note: String?, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    Layout(
        content = {
            Text(label, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = type.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val labelWant = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val noteWant = measurables.getOrNull(1)?.let { it.maxIntrinsicWidth(Constraints.Infinity) + gap } ?: 0
        val labelWidth = if (labelWant + noteWant <= width) labelWant else maxOf(width - noteWant, minOf(labelWant, width / 2))
        val labelPlaceable = measurables[0].measure(Constraints(maxWidth = labelWidth.coerceIn(0, width)))
        val notePlaceable = measurables.getOrNull(1)?.measure(Constraints(maxWidth = (width - labelPlaceable.width - gap).coerceAtLeast(0)))
        val height = maxOf(labelPlaceable.height, notePlaceable?.height ?: 0)
        layout(width, height) {
            labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
            notePlaceable?.place(labelPlaceable.width + gap, (height - notePlaceable.height) / 2)
        }
    }
}

/**
 * Skip skips the policy alarms of the occurrence (for their whole date) and deletes its one-offs; Undo re-saves the
 * one-offs and removes the skips. Unskip (skipped entries are policy alarms only) removes the skips.
 */
private fun toggleSkip(session: ScreenSession, repository: ScheduleRepository, labels: Labels, entry: Timeline.Entry) {
    val res = labels.res
    val policies = entry.occurrence.sources.filterIsInstance<Source.PolicyRule>()
    if (entry.skipped) {
        session.write(res.getString(R.string.today_unskip_failed), action = { policies.forEach { repository.unskip(it.policyId, it.date) } })
        return
    }
    val oneOffIds = entry.occurrence.sources.filterIsInstance<Source.OneOffAlarm>().map { it.oneOffId }
    val time = labels.formats.time(entry.occurrence.at.toLocalTime())
    session.write(
        res.getString(R.string.today_skip_failed),
        action = {
            val removed: List<OneOffEntity> = oneOffIds.mapNotNull { repository.oneOff(it) }
            repository.skipOccurrence(entry.occurrence.sources.map { it.key })
            removed to policiesRingingAgain(repository.snapshot(), removed.map { it.toModel() })
        },
        onSuccess = { (removed, ringingAgain) ->
            session.message(
                UiMessage(skipMessage(res, time, entry.occurrence.label, ringingAgain), actionLabel = res.getString(R.string.common_undo)) {
                    session.write(
                        res.getString(R.string.today_undo_skip_failed),
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
