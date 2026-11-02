package com.replydev.reveille.ui.oneoff

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.OneOffEntity
import com.replydev.reveille.engine.DayAlarm
import com.replydev.reveille.engine.OneOffMode
import com.replydev.reveille.engine.ScheduleEngine
import com.replydev.reveille.engine.Source
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.IconCircleButton
import com.replydev.reveille.ui.components.IconField
import com.replydev.reveille.ui.components.LeaveWhenSaved
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PickDateDialog
import com.replydev.reveille.ui.components.PickTimeDialog
import com.replydev.reveille.ui.components.PillButton
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.SegmentedControl
import com.replydev.reveille.ui.components.SheetScaffold
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.borderOutline
import com.replydev.reveille.ui.components.dashedBorder
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.lineBox
import com.replydev.reveille.ui.theme.policyColor
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

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

    // Follows the clock (and midnight) while the screen is open: the past-time check and the date strip.
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
    val save = {
        if (isInPast(date, time)) {
            session.message(UiMessage("This time is in the past."))
        } else {
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
        }
    }

    SheetScaffold(
        title = if (editId == 0L) "One-off alarm" else "Edit alarm",
        onClose = onDone,
        snackbarHostState = snackbar,
        actions = {
            if (editId != 0L) {
                IconCircleButton(
                    R.drawable.ic_delete, "Delete alarm", { confirmDelete = true },
                    container = Color.Transparent, enabled = loaded && !session.busy,
                )
            }
        },
    ) {
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
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TimeBoxes(time) { time = it }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Overline("Date", Modifier.padding(horizontal = 20.dp))
                    DateStrip(today, date) { date = it }
                }
                IconField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "Label (optional)",
                    icon = R.drawable.ic_label,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    height = 52.dp,
                    radius = 16.dp,
                    textStyle = ReveilleTheme.type.input,
                )
                Column(Modifier.padding(horizontal = 20.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Overline(modeOverline(date, today))
                    SegmentedControl(listOf("Replace the day", "Ring in addition"), if (replaceDay) 0 else 1, { replaceDay = it == 0 })
                    if (sameDay.isNotEmpty()) {
                        if (replacedByOther && !replaceDay) {
                            Text(
                                "Another alarm on this day already replaces its recurring alarms, so they won't ring.",
                                style = ReveilleTheme.type.caption,
                                color = ReveilleTheme.colors.muted,
                            )
                        } else {
                            sameDay.forEach { ImpactRow(it, replaced = replaceDay) }
                        }
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(inPast, enter = Motion.expand, exit = Motion.collapse) {
                    Text("This time is in the past.", style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.dangerText)
                }
                PillButton(
                    setForText(date, time, today),
                    save,
                    Modifier.fillMaxWidth(),
                    height = 56.dp,
                    enabled = !session.busy && !inPast,
                    textStyle = ReveilleTheme.type.button,
                )
            }
        }
    }
}

/** Hour and minute boxes; either opens the time picker (on its own unit). */
@Composable
private fun TimeBoxes(time: LocalTime, onChange: (LocalTime) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) } // true: minutes first
    var minuteFocused by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeBox("%02d".format(time.hour), "Hour, %02d".format(time.hour), focused = !minuteFocused) {
            minuteFocused = false
            picking = false
        }
        Text(":", Modifier.lineBox(ReveilleTheme.type.displayColon, 1f), style = ReveilleTheme.type.displayColon, color = ReveilleTheme.colors.faint)
        TimeBox("%02d".format(time.minute), "Minute, %02d".format(time.minute), focused = minuteFocused) {
            minuteFocused = true
            picking = true
        }
    }
    picking?.let { minuteFirst ->
        PickTimeDialog(time, onDismiss = { picking = null }, minuteFirst = minuteFirst) {
            picking = null
            onChange(it)
        }
    }
}

@Composable
private fun TimeBox(value: String, description: String, focused: Boolean, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 120.dp, height = 104.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceHigh,
        contentColor = colors.onBg,
        border = BorderStroke(2.dp, if (focused) colors.accent else Color.Transparent),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(value, style = ReveilleTheme.type.displayM) }
    }
}

/** Today and the next seven days, then a calendar tile for any other date (which it shows once picked). */
@Composable
private fun DateStrip(today: LocalDate, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val dates = quickDates(today)
    var picking by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = dates.indexOf(selected).coerceAtLeast(0).let { (it - 2).coerceAtLeast(0) })
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(dates, key = { it.toEpochDay() }) { day ->
            DateTile(day, TimeText.dayHeader(day, today), selected = day == selected) { onPick(day) }
        }
        item(key = "other") {
            val other = selected.takeIf { it !in dates }
            if (other != null) {
                DateTile(other, TimeText.dayHeader(other, today), selected = true) { picking = true }
            } else {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 68.dp)
                        .dashedBorder(ReveilleTheme.colors.outlineDashed, 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconCircleButton(
                        R.drawable.ic_calendar, "Pick another date", { picking = true },
                        size = 54.dp, iconSize = 20.dp, container = Color.Transparent,
                    )
                }
            }
        }
    }
    if (picking) {
        PickDateDialog(selected, onDismiss = { picking = false }) {
            picking = false
            onPick(it)
        }
    }
}

@Composable
private fun DateTile(day: LocalDate, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 56.dp, height = 68.dp).semantics {
            contentDescription = description
            this.selected = selected
        },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.accent else colors.surfaceHigh,
        contentColor = if (selected) colors.onAccent else colors.onBg,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            Text(day.format(weekday).uppercase(), Modifier.alpha(0.85f), style = ReveilleTheme.type.dayOverline)
            Text("${day.dayOfMonth}", style = ReveilleTheme.type.dayNumber)
        }
    }
}

/** A policy alarm on the chosen day: struck through when the one-off replaces the day. */
@Composable
private fun ImpactRow(alarm: DayAlarm, replaced: Boolean) {
    val colors = ReveilleTheme.colors
    val policyId = (alarm.source as? Source.PolicyRule)?.policyId
    Row(
        Modifier.fillMaxWidth().borderOutline(14.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            TimeText.time(alarm.time),
            style = ReveilleTheme.type.timeSmall.copy(textDecoration = if (replaced) TextDecoration.LineThrough else null),
            color = colors.muted,
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            policyId?.let { PolicyDot(policyColor(it)) }
            Text(alarm.label, style = ReveilleTheme.type.body, maxLines = 1)
        }
        Text(if (replaced) "won't ring" else "also rings", style = ReveilleTheme.type.small, color = colors.muted)
    }
}
