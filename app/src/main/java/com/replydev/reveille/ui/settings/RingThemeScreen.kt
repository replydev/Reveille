package com.replydev.reveille.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.alarm.RingActions
import com.replydev.reveille.alarm.RingTheme
import com.replydev.reveille.alarm.RingingActivity
import com.replydev.reveille.alarm.RingingContent
import com.replydev.reveille.alarm.sampleRingState
import com.replydev.reveille.container
import com.replydev.reveille.ui.components.EditorTopBar
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.Pill
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.UiMessage
import com.replydev.reveille.ui.components.rememberScreenSession
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.reliability.scheduleTestAlarm
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.policyColor
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@get:StringRes
val RingTheme.nameRes: Int
    get() = when (this) {
        RingTheme.DAWN -> R.string.ring_theme_dawn
        RingTheme.MIDNIGHT -> R.string.ring_theme_midnight
        RingTheme.POLICY_COLOUR -> R.string.ring_theme_policy_colour
        RingTheme.HORIZON -> R.string.ring_theme_horizon
        RingTheme.PAPER -> R.string.ring_theme_paper
    }

@get:StringRes
val RingTheme.hintRes: Int
    get() = when (this) {
        RingTheme.DAWN -> R.string.ring_theme_dawn_hint
        RingTheme.MIDNIGHT -> R.string.ring_theme_midnight_hint
        RingTheme.POLICY_COLOUR -> R.string.ring_theme_policy_colour_hint
        RingTheme.HORIZON -> R.string.ring_theme_horizon_hint
        RingTheme.PAPER -> R.string.ring_theme_paper_hint
    }

/** A "Per policy" row: the policy and its override, null for "Default". */
@Immutable
data class PolicyThemeRow(val id: Long, val name: String, val theme: RingTheme?)

/** One row per policy, in [policies]' order; overrides of policies that no longer exist are ignored. */
fun policyThemeRows(policies: List<Pair<Long, String>>, overrides: Map<Long, RingTheme>): List<PolicyThemeRow> =
    policies.map { (id, name) -> PolicyThemeRow(id, name, overrides[id]) }

/** The mockups' frame: thumbnails render the real theme at this size and scale it down. */
private val FrameWidth = 390.dp
private val FrameHeight = 844.dp
private const val ThumbnailScale = 0.3846f
private val ThumbnailWidth = 150.dp
private val ThumbnailHeight = 325.dp
private val ThumbnailRadius = 22.dp

/** Settings › Ringing theme (docs/design/ringing-themes.md §4, RingPicker.dc.html). Every choice saves immediately. */
@Composable
fun RingThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(snackbar)
    val labels = rememberLabels()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val policies by container.repository.policies.collectAsStateWithLifecycle(initialValue = null)
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val saveFailed = stringResource(R.string.ring_theme_save_failed)
    val testFailed = stringResource(R.string.health_test_failed)
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = { EditorTopBar(stringResource(R.string.settings_ringing_theme), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { padding ->
        // Wait for the stored values: showing Dawn first would let a quick tap overwrite the real choice.
        val current = settings ?: return@Scaffold
        val rows = policyThemeRows(policies.orEmpty().map { it.policy.id to it.policy.name }, current.policyRingThemes)
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            val sampleLabel = stringResource(R.string.ring_theme_sample_label)
            // The mockups' sample: 07:55 on a fixed day, not the live clock.
            val sample = remember(sampleLabel) { sampleRingState(sampleLabel, LocalDate.of(2026, 11, 3).atTime(LocalTime.of(7, 55)).atZone(ZoneId.systemDefault())) }
            val group = stringResource(R.string.ring_theme_group)
            LazyRow(
                Modifier.padding(top = 12.dp).selectableGroup().semantics { contentDescription = group },
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(RingTheme.entries, key = { it.name }) { theme ->
                    ThemeItem(theme, selected = theme == current.ringTheme, sample) {
                        session.write(saveFailed, guarded = false, action = { container.settings.update { it.copy(ringTheme = theme) } })
                    }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(R.drawable.ic_phone, stringResource(R.string.ring_theme_preview), outlined = true, Modifier.weight(1f)) {
                    context.startActivity(RingingActivity.previewIntent(context, current.ringTheme))
                }
                ActionPill(R.drawable.ic_bell, stringResource(R.string.ring_theme_test), outlined = false, Modifier.weight(1f)) {
                    session.write(testFailed, action = { scheduleTestAlarm(container, labels) }, onSuccess = { session.message(UiMessage(it)) })
                }
            }
            if (rows.isNotEmpty()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Overline(stringResource(R.string.ring_theme_per_policy), Modifier.weight(1f))
                        Text(stringResource(R.string.ring_theme_one_offs_default), style = type.small, color = colors.muted)
                    }
                    ReveilleCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                        rows.forEachIndexed { index, row -> PolicyRow(row, divider = index > 0) { editing = row.id } }
                    }
                }
            }
        }
        rows.firstOrNull { it.id == editing }?.let { row ->
            PolicyThemeSheet(row, onDismiss = { editing = null }) { theme ->
                editing = null
                val live = rows.map { it.id }.toSet()
                session.write(saveFailed, guarded = false, action = { container.settings.setPolicyRingTheme(row.id, theme, live) })
            }
        }
    }
}

/** A theme thumbnail (the real composable, scaled) with its name and hint; a radio button for accessibility. */
@Composable
private fun ThemeItem(theme: RingTheme, selected: Boolean, sample: com.replydev.reveille.alarm.RingState, onSelect: () -> Unit) {
    val colors = ReveilleTheme.colors
    Column(
        Modifier.width(ThumbnailWidth).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(ThumbnailWidth, ThumbnailHeight).selectionRing(selected)) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(ThumbnailRadius))) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(FrameWidth, FrameHeight)
                        .graphicsLayer {
                            scaleX = ThumbnailScale
                            scaleY = ThumbnailScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .clearAndSetSemantics { },
                ) {
                    RingingContent(theme, sample, NoActions, motion = false)
                }
            }
            // Taps on the thumbnail select it; they never reach the buttons drawn inside.
            Box(Modifier.matchParentSize().pointerInput(onSelect) { detectTapGestures { onSelect() } })
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ReveilleIcon(R.drawable.ic_check, null, tint = colors.onAccent, size = 16.dp)
                }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(theme.nameRes), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
            Text(stringResource(theme.hintRes), style = ReveilleTheme.type.small, color = colors.muted)
        }
    }
}

private val NoActions = RingActions(onDismiss = {}, onSnooze = {})

/** Selected: a 2 dp accent ring 3 dp outside the thumbnail. Otherwise a 1 dp outline just outside it. */
@Composable
private fun Modifier.selectionRing(selected: Boolean): Modifier {
    val color = if (selected) ReveilleTheme.colors.accent else ReveilleTheme.colors.outline
    val width = if (selected) 2.dp else 1.dp
    val gap = if (selected) 3.dp else 0.dp
    return drawWithContent {
        drawContent()
        val stroke = width.toPx()
        val inset = gap.toPx() + stroke / 2
        drawRoundRect(
            color,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + inset * 2, size.height + inset * 2),
            cornerRadius = CornerRadius(ThumbnailRadius.toPx() + inset),
            style = Stroke(stroke),
        )
    }
}

@Composable
private fun ActionPill(icon: Int, text: String, outlined: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Pill(
        onClick = onClick,
        modifier = modifier,
        container = if (outlined) Color.Transparent else colors.surfaceHigh,
        content = colors.onBg,
        border = if (outlined) androidx.compose.foundation.BorderStroke(1.dp, colors.outlineStrong) else null,
        height = 48.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReveilleIcon(icon, null, tint = colors.onBg, size = 18.dp)
            Text(text, style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PolicyRow(row: PolicyThemeRow, divider: Boolean, onClick: () -> Unit) {
    val colors = ReveilleTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (divider) Modifier.topDivider(colors.outline) else Modifier)
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PolicyDot(policyColor(row.id), size = 10.dp, radius = 3.dp)
        Text(row.name, Modifier.weight(1f), style = ReveilleTheme.type.bodyL, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(row.theme?.nameRes ?: R.string.ring_theme_default),
            style = ReveilleTheme.type.body,
            color = if (row.theme == null) colors.muted else colors.onBg,
        )
        ReveilleIcon(R.drawable.ic_chevron_right, null, tint = colors.muted, size = 16.dp)
    }
}

/** The per-policy choice: Default, then the five themes. Picking one saves it and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyThemeSheet(row: PolicyThemeRow, onDismiss: () -> Unit, onPick: (RingTheme?) -> Unit) {
    val colors = ReveilleTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = colors.sheetBg,
        contentColor = colors.onBg,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).selectableGroup()) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PolicyDot(policyColor(row.id), size = 10.dp, radius = 3.dp)
                Text(row.name, style = ReveilleTheme.type.titleSheet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            (listOf<RingTheme?>(null) + RingTheme.entries).forEach { theme ->
                val selected = theme == row.theme
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = SheetRowHeight)
                        .selectable(selected, role = Role.RadioButton) { onPick(theme) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted))
                    Text(stringResource(theme?.nameRes ?: R.string.ring_theme_default), style = ReveilleTheme.type.bodyL)
                }
            }
        }
    }
}

private val SheetRowHeight: Dp = 52.dp
