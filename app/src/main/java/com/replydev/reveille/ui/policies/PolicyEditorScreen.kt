package com.replydev.reveille.ui.policies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.PolicyEntity
import com.replydev.reveille.data.PolicyWithChildren
import com.replydev.reveille.data.RuleEntity
import com.replydev.reveille.data.toModel
import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.text.Labels
import com.replydev.reveille.ui.components.ConfirmDialog
import com.replydev.reveille.ui.components.DayToggleRow
import com.replydev.reveille.ui.components.EditorTopBar
import com.replydev.reveille.ui.components.IconCircleButton
import com.replydev.reveille.ui.components.IconField
import com.replydev.reveille.ui.components.LabeledField
import com.replydev.reveille.ui.components.LeaveWhenSaved
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.PickDateDialog
import com.replydev.reveille.ui.components.PickTimeDialog
import com.replydev.reveille.ui.components.PillButton
import com.replydev.reveille.ui.components.PillStepper
import com.replydev.reveille.ui.components.PillStyle
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.ReveilleSwitch
import com.replydev.reveille.ui.components.TextAction
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.borderOutline
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.rememberToday
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.animatedItem
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.lineBox
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
    val labels = rememberLabels()
    val res = labels.res

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
                session.message(UiMessage(res.getString(R.string.policy_deleted_recreate)))
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
    val today = rememberToday()
    val snapshot by repository.snapshots.collectAsStateWithLifecycle(initialValue = null)
    val ruleList = rules.map { it.rule }
    val pauseList = pauses.map { it.pause }
    val errors = validatePolicy(name, startDate, endDate, ruleList, pauseList)
    val draftPolicy = PolicyEntity(id = editId, name = name.trim(), enabled = enabled, startDate = startDate, endDate = endDate)
    val zone = ZoneId.systemDefault()
    val warning = if (loaded && errors.isEmpty()) upcomingWarning(res, draftPolicy, ruleList, pauseList, Instant.now(), zone) else null
    val next = if (loaded && errors.isEmpty() && warning == null) {
        nextPolicyAlarm(PolicyWithChildren(draftPolicy, ruleList, pauseList).toModel(), snapshot ?: ScheduleSnapshot(), Instant.now(), zone)
    } else {
        null
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = ReveilleTheme.colors.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            EditorTopBar(stringResource(if (editId == 0L) R.string.policy_new else R.string.policy_edit), onBack = onDone) {
                if (editId != 0L) {
                    IconCircleButton(
                        R.drawable.ic_delete, stringResource(R.string.policy_delete), { confirmDelete = true },
                        size = 48.dp, iconSize = 20.dp, container = Color.Transparent, enabled = loaded && !session.busy,
                    )
                }
            }
        },
        bottomBar = {
            SaveBar(
                errors = errors,
                warning = warning,
                next = next?.let { nextText(labels, it.at, today) },
                enabled = loaded && !session.busy && errors.isEmpty(),
                onSave = {
                    val toSave = ruleList.map { it.copy(label = it.label?.trim()?.ifEmpty { null }) }
                    session.write(
                        res.getString(R.string.policy_save_failed),
                        action = { repository.savePolicy(draftPolicy, toSave, pauseList) },
                        onSuccess = { savedId ->
                            // Set before anything else: if the screen stays open, saving again updates this policy.
                            session.editId = savedId
                            session.saved = true
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (confirmDelete) {
            ConfirmDialog(
                title = stringResource(R.string.policy_delete_title),
                text = stringResource(R.string.policy_delete_text),
                confirmLabel = stringResource(R.string.common_delete),
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    val target = editId
                    session.write(
                        res.getString(R.string.policy_delete_failed),
                        action = { repository.deletePolicy(target) },
                        onSuccess = { session.saved = true },
                    )
                },
            )
        }
        if (!loaded) return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(stringResource(R.string.policy_name), name, { name = it }, Modifier.weight(1f))
                    Column(
                        Modifier
                            .width(84.dp)
                            .fillMaxHeight()
                            .background(ReveilleTheme.colors.surface, RoundedCornerShape(16.dp))
                            .borderOutline(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        Text(stringResource(R.string.policy_enabled), style = ReveilleTheme.type.small, color = ReveilleTheme.colors.muted)
                        ReveilleSwitch(enabled, { enabled = it }, small = true)
                    }
                }
            }
            item(key = "dates") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateTile(
                        stringResource(R.string.policy_active_from), stringResource(R.string.policy_clear_active_from), labels,
                        startDate, { startDate = it }, Modifier.weight(1f),
                    )
                    DateTile(
                        stringResource(R.string.policy_active_until), stringResource(R.string.policy_clear_active_until), labels,
                        endDate, { endDate = it }, Modifier.weight(1f),
                    )
                }
            }
            item(key = "rules-title") { Overline(stringResource(R.string.policy_alarm_times), Modifier.padding(start = 4.dp, top = 10.dp)) }
            itemsIndexed(rules, key = { _, draft -> "rule-${draft.key}" }) { index, draft ->
                RuleCard(
                    draft.rule,
                    labels,
                    onChange = { rules[index] = draft.copy(rule = it) },
                    onRemove = { rules.removeAt(index) },
                    modifier = Modifier.animatedItem(this),
                )
            }
            item(key = "add") {
                Row(Modifier.animatedItem(this), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddButton(stringResource(R.string.policy_add_alarm_time), Modifier.weight(1f)) { rules.add(newRule()) }
                    AddButton(stringResource(R.string.policy_add_pause), Modifier.weight(1f)) {
                        val start = LocalDate.now()
                        pauses.add(PauseDraft(newDraftKey(), PauseEntity(policyId = editId, fromDate = start, toDate = start.plusDays(7))))
                    }
                }
            }
            itemsIndexed(pauses, key = { _, draft -> "pause-${draft.key}" }) { index, draft ->
                PauseRow(
                    draft.pause,
                    today,
                    labels,
                    onChange = { pauses[index] = draft.copy(pause = it) },
                    onRemove = { pauses.removeAt(index) },
                    modifier = Modifier.animatedItem(this),
                )
            }
        }
    }
}

@Composable
private fun AddButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    PillButton(text, onClick, modifier, style = PillStyle.Dashed, height = 44.dp, textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.Medium))
}

/** The sticky bar: "Next: …", or the warning in the accent and the errors in danger, beside Save. */
@Composable
private fun SaveBar(errors: List<PolicyError>, warning: String?, next: String?, enabled: Boolean, onSave: () -> Unit) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .topDivider(colors.surfaceHigh)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            errors.forEach { Text(stringResource(it.message), style = type.caption, color = colors.dangerText) }
            warning?.let { Text(it, style = type.caption, color = colors.accent) }
            if (next != null) {
                val line = stringResource(R.string.policy_next, next)
                val at = line.indexOf(next)
                Text(
                    buildAnnotatedString {
                        append(line)
                        if (at >= 0) addStyle(SpanStyle(color = colors.onBg), at, at + next.length)
                    },
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }
        PillButton(
            stringResource(R.string.common_save), onSave,
            height = 52.dp,
            enabled = enabled,
            textStyle = type.button,
            contentPadding = PaddingValues(horizontal = 32.dp),
        )
    }
}

/** "Active from" / "Active until": "Always" when unset; tap to pick, × to clear. */
@Composable
private fun DateTile(label: String, clearDescription: String, labels: Labels, date: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        contentColor = colors.onBg,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = ReveilleTheme.type.small, color = colors.muted)
                Text(date?.let(labels.formats::fullDate) ?: stringResource(R.string.policy_always), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium), maxLines = 1)
            }
            if (date != null) {
                IconCircleButton(R.drawable.ic_close, clearDescription, { onChange(null) }, size = 32.dp, iconSize = 14.dp, container = Color.Transparent)
            }
        }
    }
    if (open) {
        PickDateDialog(date ?: LocalDate.now(), onDismiss = { open = false }) {
            open = false
            onChange(it)
        }
    }
}

@Composable
private fun RuleCard(rule: RuleEntity, labels: Labels, onChange: (RuleEntity) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    var pickTime by rememberSaveable { mutableStateOf(false) }
    ReveilleCard(modifier.fillMaxWidth(), radius = 20.dp, border = colors.accentLine, spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                labels.formats.time(rule.time),
                Modifier.lineBox(type.displayS, 1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.policy_change_time)) { pickTime = true },
                style = type.displayS,
            )
            Spacer(Modifier.weight(1f))
            IconCircleButton(R.drawable.ic_close, stringResource(R.string.policy_remove_alarm_time), onRemove)
        }
        DayToggleRow(rule.days, { onChange(rule.copy(days = it)) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.policy_repeat_every), Modifier.weight(1f), style = type.body, color = colors.muted)
            PillStepper(
                value = pluralStringResource(R.plurals.policy_weeks, rule.intervalWeeks, rule.intervalWeeks),
                onMinus = { onChange(rule.copy(intervalWeeks = (rule.intervalWeeks - 1).coerceAtLeast(1))) },
                onPlus = { onChange(rule.copy(intervalWeeks = (rule.intervalWeeks + 1).coerceAtMost(MAX_INTERVAL_WEEKS))) },
                minusDescription = stringResource(R.string.policy_fewer_weeks),
                plusDescription = stringResource(R.string.policy_more_weeks),
            )
        }
        AnimatedVisibility(rule.intervalWeeks > 1, enter = Motion.expand, exit = Motion.collapse) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.policy_including_week_of), Modifier.weight(1f), style = type.body, color = colors.muted)
                DatePill(rule.anchorDate, labels) { onChange(rule.copy(anchorDate = it)) }
            }
        }
        IconField(
            value = rule.label.orEmpty(),
            // ifEmpty, not ifBlank: a space typed between words must not vanish. Trimmed on save.
            onValueChange = { onChange(rule.copy(label = it.ifEmpty { null })) },
            placeholder = stringResource(R.string.policy_label_optional),
            icon = R.drawable.ic_label,
        )
    }
    if (pickTime) {
        PickTimeDialog(rule.time, onDismiss = { pickTime = false }) {
            pickTime = false
            onChange(rule.copy(time = it))
        }
    }
}

/** A date in a [PillStyle.Secondary] pill that opens the date picker. */
@Composable
private fun DatePill(date: LocalDate, labels: Labels, onChange: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PillButton(labels.formats.fullDate(date), { open = true }, style = PillStyle.Secondary, height = 40.dp, textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.Medium))
    if (open) {
        PickDateDialog(date, onDismiss = { open = false }) {
            open = false
            onChange(it)
        }
    }
}

@Composable
private fun PauseRow(pause: PauseEntity, today: LocalDate, labels: Labels, onChange: (PauseEntity) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    var editing by rememberSaveable { mutableStateOf<Boolean?>(null) } // true: from, false: to
    Row(
        modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReveilleIcon(R.drawable.ic_calendar, null, tint = colors.muted, size = 16.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.policy_paused) + " ", style = ReveilleTheme.type.body)
            PauseDate(shortDate(labels, pause.fromDate, today), stringResource(R.string.policy_change_pause_start)) { editing = true }
            Text(" → ", style = ReveilleTheme.type.body, color = colors.muted)
            PauseDate(shortDate(labels, pause.toDate, today), stringResource(R.string.policy_change_pause_end)) { editing = false }
        }
        TextAction(stringResource(R.string.common_remove), onRemove)
    }
    editing?.let { from ->
        PickDateDialog(if (from) pause.fromDate else pause.toDate, onDismiss = { editing = null }) {
            editing = null
            onChange(if (from) pause.copy(fromDate = it) else pause.copy(toDate = it))
        }
    }
}

/** A tappable date inside the pause row; underlined so it reads as editable. */
@Composable
private fun PauseDate(text: String, clickLabel: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick).padding(vertical = 8.dp),
        style = ReveilleTheme.type.body.copy(textDecoration = TextDecoration.Underline),
    )
}
