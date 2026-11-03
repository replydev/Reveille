package com.replydev.reveille.alarm

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replydev.reveille.R
import com.replydev.reveille.engine.TimeText
import com.replydev.reveille.ui.components.Pill
import com.replydev.reveille.ui.components.PolicyDot
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.TimeDisplay
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.ReveilleTheme
import com.replydev.reveille.ui.theme.policyColor
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/**
 * What a ringing screen shows, as values. The same state renders in every [RingTheme]; a theme only changes the look.
 *
 * [now] is the live clock (and its zone). [policyId] is the alarm's policy (or the snoozed alarm's), null for one-offs
 * and the test alarm; [policy] is what was read about it, null until read or when it no longer exists. [backupOf] is
 * the primary alarm's time when its backup is still to come. Null snooze or auto-snooze minutes are simply not shown.
 */
@Immutable
data class RingState(
    val now: ZonedDateTime,
    val title: String,
    val policyId: Long? = null,
    val policy: PolicyRingInfo? = null,
    val policyGone: Boolean = false,
    val backupOf: Instant? = null,
    val snoozeMinutes: Int? = null,
    val autoSnoozeMinutes: Int? = null,
    val ringingFor: Duration = Duration.ZERO,
)

/** [onSnooze] is null in fallback mode: there is no Snooze button then. */
@Immutable
class RingActions(val onDismiss: () -> Unit, val onSnooze: (() -> Unit)?)

/** Whether the system has animations on (the ringing screen's only motion is decorative). */
@Composable
fun rememberSystemMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/**
 * The full-screen alarm in [theme]. Stateless: [RingingActivity] feeds it the live state, and the theme picker feeds
 * it sample data for its thumbnails. [motion] turns the decorative animations on.
 */
@Composable
fun RingingContent(theme: RingTheme, state: RingState, actions: RingActions, modifier: Modifier = Modifier, motion: Boolean = rememberSystemMotion()) {
    val root = modifier.fillMaxSize().testTag(ringThemeTag(theme))
    when (theme) {
        RingTheme.DAWN -> Dawn(state, actions, motion, root)
        RingTheme.MIDNIGHT -> Midnight(state, actions, root)
        RingTheme.POLICY_COLOUR -> PolicyColour(state, actions, root)
        RingTheme.HORIZON -> Horizon(state, actions, motion, root)
        RingTheme.PAPER -> Paper(state, actions, root)
    }
}

/** The mockup's top / bottom padding, or more where the system bars need it. */
@Composable
private fun screenPadding(horizontal: Dp, top: Dp, bottom: Dp): PaddingValues {
    val insets = WindowInsets.systemBars.asPaddingValues()
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = maxOf(top, insets.calculateTopPadding() + 8.dp),
        bottom = maxOf(bottom, insets.calculateBottomPadding() + 16.dp),
    )
}

@Composable
private fun snoozeLabel(minutes: Int?): String =
    minutes?.let { pluralStringResource(R.plurals.ringing_snooze_minutes, it, it) } ?: stringResource(R.string.ringing_snooze)

/** A theme's upper part takes what the buttons leave: on a short screen it is clipped, never the buttons. */
@Composable
private fun ColumnScope.Top(horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().clipToBounds(), horizontalAlignment = horizontalAlignment, content = content)
}

/** A themed clock shrinks to fit a narrow screen (or a 12-hour time) rather than clip; it never grows past [style]. */
private fun fitClock(style: TextStyle): TextAutoSize = TextAutoSize.StepBased(minFontSize = 48.sp, maxFontSize = style.fontSize, stepSize = 2.sp)

/** A ringing-screen button: a [shape] exactly [height] tall. */
@Composable
private fun RingButton(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    height: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    body: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(onClick = onClick, modifier = modifier.height(height), shape = shape, color = container, contentColor = content, border = border) {
            Box(contentAlignment = Alignment.Center) { body() }
        }
    }
}

// --- Dawn: the original design, unchanged ---------------------------------------------------------------------------

@Composable
private fun Dawn(state: RingState, actions: RingActions, motion: Boolean, modifier: Modifier) {
    val labels = rememberLabels()
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    Box(modifier.background(colors.accent)) {
        RingCircles(motion, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(screenPadding(24.dp, top = 96.dp, bottom = 40.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Top(Alignment.CenterHorizontally) {
                Text(labels.formats.headerDate(state.now.toLocalDate()).uppercase(labels.formats.locale), style = type.ringOverline, color = colors.onAccent)
                TimeDisplay(
                    labels.formats.timeParts(state.now.toLocalTime()),
                    type.displayXl,
                    type.titleS,
                    Modifier.padding(top = 18.dp),
                    color = colors.onAccent,
                )
                Row(
                    Modifier.padding(top = 18.dp).height(36.dp).background(colors.onAccent.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReveilleIcon(R.drawable.ic_bell, null, tint = colors.onAccent, size = 18.dp)
                    Text(state.title, style = type.input.copy(fontWeight = FontWeight.Medium), color = colors.onAccent, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Pill(onClick = actions.onDismiss, modifier = Modifier.fillMaxWidth(), container = colors.onAccent, content = colors.accent, height = 76.dp) {
                    Text(stringResource(R.string.ringing_dismiss), style = type.ringButton)
                }
                actions.onSnooze?.let { onSnooze ->
                    Pill(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth(),
                        container = Color.Transparent,
                        content = colors.onAccent,
                        border = BorderStroke(2.dp, colors.onAccent),
                        height = 76.dp,
                    ) {
                        Text(snoozeLabel(state.snoozeMinutes), style = type.ringButton)
                    }
                    state.autoSnoozeMinutes?.let {
                        Text(
                            pluralStringResource(R.plurals.ringing_auto_snooze, it, it),
                            Modifier.padding(top = 6.dp).alpha(0.8f),
                            style = type.caption,
                            color = colors.onAccent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The three concentric circles behind the lower half (520/360/200 dp, centred where the mockup puts them). They
 * breathe slowly when [motion] is on.
 */
@Composable
private fun RingCircles(motion: Boolean, modifier: Modifier = Modifier) {
    val onAccent = ReveilleTheme.colors.onAccent
    val pulse = if (motion) {
        val transition = rememberInfiniteTransition(label = "ring pulse")
        transition.animateFloat(1f, 1.06f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale").value
    } else {
        1f
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height * (560f / 844f))
        val stroke = Stroke(1.dp.toPx())
        drawCircle(onAccent.copy(alpha = 0.10f), radius = 260.dp.toPx() * pulse, center = center, style = stroke)
        drawCircle(onAccent.copy(alpha = 0.14f), radius = 180.dp.toPx() * (1f + (pulse - 1f) * 1.5f), center = center, style = stroke)
        drawCircle(onAccent.copy(alpha = 0.06f), radius = 100.dp.toPx() * (1f + (pulse - 1f) * 2f), center = center)
    }
}

// --- Midnight ---------------------------------------------------------------------------------------------------------

@Composable
private fun Midnight(state: RingState, actions: RingActions, modifier: Modifier) {
    val labels = rememberLabels()
    val colors = ReveilleTheme.colors
    Column(modifier.background(RingColors.MidnightBg).padding(screenPadding(28.dp, top = 112.dp, bottom = 40.dp))) {
        Top {
            Text(labels.formats.headerDate(state.now.toLocalDate()).uppercase(labels.formats.locale), style = RingType.midnightOverline, color = RingColors.MidnightMuted)
            TimeDisplay(
                labels.formats.timeParts(state.now.toLocalTime()),
                RingType.midnightClock,
                RingType.period(FontWeight.Light),
                Modifier.padding(top = 16.dp),
                color = colors.accent,
                autoSize = fitClock(RingType.midnightClock),
            )
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PolicyDot(state.policyId?.let(::policyColor) ?: colors.accent, size = 10.dp, radius = 3.dp)
                Text(state.title, style = RingType.midnightLabel, color = RingColors.MidnightText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            state.backupOf?.let {
                Text(backupRingText(labels, it, state.now.zone), Modifier.padding(top = 6.dp), style = RingType.midnightBackup, color = RingColors.MidnightMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RingButton(actions.onDismiss, colors.accentContainer, colors.accent, 80.dp, CircleShape, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ringing_dismiss), style = RingType.midnightDismiss)
            }
            actions.onSnooze?.let { onSnooze ->
                RingButton(onSnooze, Color.Transparent, RingColors.MidnightText, 80.dp, CircleShape, Modifier.fillMaxWidth(), border = BorderStroke(1.5.dp, colors.accentLine)) {
                    Text(snoozeLabel(state.snoozeMinutes), style = RingType.midnightSnooze)
                }
                state.autoSnoozeMinutes?.let {
                    Text(
                        pluralStringResource(R.plurals.ringing_midnight_footnote, it, it),
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = ReveilleTheme.type.caption,
                        color = RingColors.MidnightMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// --- Policy colour ----------------------------------------------------------------------------------------------------

@Composable
private fun PolicyColour(state: RingState, actions: RingActions, modifier: Modifier) {
    val labels = rememberLabels()
    val colors = ReveilleTheme.colors
    // Without a policy (a one-off, the test alarm, a policy deleted meanwhile) the screen is the accent.
    val hasPolicy = state.policyId != null && !state.policyGone
    val background = if (hasPolicy) policyColor(state.policyId!!) else colors.accent
    val ink = if (hasPolicy) RingColors.PolicyInk else colors.onAccent
    val policy = state.policy.takeIf { hasPolicy }
    Column(modifier.background(background).padding(screenPadding(28.dp, top = 96.dp, bottom = 36.dp))) {
        Top {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(policy?.name ?: state.title, Modifier.weight(1f), style = RingType.policyName, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                policy?.days?.let { days ->
                    Box(
                        Modifier.height(32.dp).border(1.5.dp, ink.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(labels.days(TimeText.daySetOf(days)), style = RingType.policyDays, color = ink, maxLines = 1)
                    }
                }
            }
            Text(labels.formats.headerDate(state.now.toLocalDate()), Modifier.padding(top = 6.dp), style = RingType.policyLine, color = ink.copy(alpha = 0.8f))
            Box(Modifier.padding(top = 40.dp).fillMaxWidth().height(1.5.dp).background(ink.copy(alpha = 0.25f)))
            TimeDisplay(
                labels.formats.timeParts(state.now.toLocalTime()),
                RingType.policyClock,
                RingType.period(FontWeight.Light),
                Modifier.padding(top = 28.dp).offset(x = (-6).dp),
                color = ink,
                autoSize = fitClock(RingType.policyClock),
            )
            policy?.next?.let { next ->
                Text(
                    stringResource(R.string.ringing_next, labels.dayAndTime(next, state.now.toInstant(), state.now.zone)),
                    Modifier.padding(top = 10.dp),
                    style = RingType.policyLine,
                    color = ink.copy(alpha = 0.8f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = RoundedCornerShape(28.dp)
            actions.onSnooze?.let { onSnooze ->
                RingButton(onSnooze, Color.Transparent, ink, 112.dp, shape, Modifier.weight(1f), border = BorderStroke(2.dp, ink)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ringing_snooze), style = RingType.policyButton)
                        state.snoozeMinutes?.let {
                            Text(pluralStringResource(R.plurals.ringing_snooze_length, it, it), Modifier.alpha(0.8f), style = RingType.policyButtonDetail)
                        }
                    }
                }
            }
            RingButton(actions.onDismiss, ink, background, 112.dp, shape, Modifier.weight(1f)) {
                Text(stringResource(R.string.ringing_dismiss), style = RingType.policyButton)
            }
        }
    }
}

// --- Horizon ----------------------------------------------------------------------------------------------------------

/** Mockup geometry (dp): the horizon sits 244 dp above the bottom, so the buttons always stand on the ground. */
private val HorizonFromBottom = 244.dp
private val SunDiameter = 180.dp
private val SunRise = 24.dp

@Composable
private fun Horizon(state: RingState, actions: RingActions, motion: Boolean, modifier: Modifier) {
    val labels = rememberLabels()
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    // The sun rises 24 dp over 2 s, only with system animations on.
    val rise = remember { Animatable(if (motion) SunRise.value else 0f) }
    LaunchedEffect(motion) { if (motion) rise.animateTo(0f, tween(2_000, easing = FastOutSlowInEasing)) }
    BoxWithConstraints(modifier.background(RingColors.HorizonSky)) {
        val horizon = maxHeight - HorizonFromBottom
        val bandsHeight = RingColors.HorizonBands.sumOf { it.second }.dp
        Column(Modifier.fillMaxWidth().offset(y = horizon - bandsHeight)) {
            RingColors.HorizonBands.forEach { (color, height) -> Box(Modifier.fillMaxWidth().height(height.dp).background(color)) }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (horizon - SunDiameter / 2 + rise.value.dp).roundToPx()) }
                .size(SunDiameter)
                .background(colors.accent, CircleShape),
        )
        Column(Modifier.fillMaxWidth().offset(y = horizon).fillMaxHeight().background(colors.bg)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.accent))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = screenPadding(24.dp, top = 104.dp, bottom = 0.dp).calculateTopPadding(), start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(labels.formats.headerDate(state.now.toLocalDate()).uppercase(labels.formats.locale), style = type.ringOverline, color = RingColors.HorizonDate)
            TimeDisplay(
                labels.formats.timeParts(state.now.toLocalTime()),
                RingType.horizonClock,
                RingType.period(FontWeight.Light),
                Modifier.padding(top = 14.dp),
                color = colors.onBg,
                autoSize = fitClock(RingType.horizonClock),
            )
            Text(state.title, Modifier.padding(top = 14.dp), style = RingType.horizonLabel, color = RingColors.HorizonLabel, maxLines = 2, textAlign = TextAlign.Center)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(screenPadding(24.dp, top = 0.dp, bottom = 32.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RingButton(actions.onDismiss, colors.accent, colors.onAccent, 72.dp, CircleShape, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ringing_dismiss), style = type.ringButton)
            }
            actions.onSnooze?.let { onSnooze ->
                RingButton(onSnooze, Color.Transparent, colors.onBg, 64.dp, CircleShape, Modifier.fillMaxWidth(), border = BorderStroke(1.5.dp, colors.outlineStrong)) {
                    Text(snoozeLabel(state.snoozeMinutes), style = RingType.horizonSnooze)
                }
            }
        }
    }
}

// --- Paper ------------------------------------------------------------------------------------------------------------

@Composable
private fun Paper(state: RingState, actions: RingActions, modifier: Modifier) {
    val labels = rememberLabels()
    val ink = RingColors.PaperInk
    Column(modifier.background(RingColors.PaperBg).padding(screenPadding(24.dp, top = 88.dp, bottom = 32.dp))) {
        Top {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_name).uppercase(labels.formats.locale), Modifier.weight(1f).alignByBaseline(), style = RingType.paperMast, color = ink)
                Text(labels.formats.shortDate(state.now.toLocalDate()), Modifier.alignByBaseline(), style = RingType.paperDate, color = ink)
            }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(2.dp).background(ink))
            TimeDisplay(
                labels.formats.timeParts(state.now.toLocalTime()),
                RingType.paperClock,
                RingType.period(FontWeight.Bold),
                Modifier.padding(top = 36.dp).offset(x = (-8).dp),
                color = ink,
                lineBox = 0.95f,
                autoSize = fitClock(RingType.paperClock),
            )
            Box(Modifier.padding(top = 28.dp).fillMaxWidth().height(1.dp).background(RingColors.PaperHairline))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                PaperCell(stringResource(R.string.ringing_paper_alarm), state.title, RingType.paperCellValue, Modifier.weight(1f).padding(end = 12.dp))
                Box(Modifier.fillMaxHeight().width(1.dp).background(RingColors.PaperHairline))
                PaperCell(
                    stringResource(R.string.ringing_paper_ringing_for),
                    ringElapsed(state.ringingFor),
                    RingType.paperCellMono,
                    Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            val progress = state.autoSnoozeMinutes?.let { paperProgress(state.ringingFor, it) } ?: 0f
            Box(Modifier.fillMaxWidth().height(4.dp).background(RingColors.PaperTrack)) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(RingColors.PaperAccent))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(4.dp)
            RingButton(actions.onDismiss, ink, RingColors.PaperBg, 76.dp, shape, Modifier.fillMaxWidth()) {
                PaperButtonRow(stringResource(R.string.ringing_dismiss)) { ReveilleIcon(R.drawable.ic_check, null, tint = RingColors.PaperBg, size = 22.dp) }
            }
            actions.onSnooze?.let { onSnooze ->
                RingButton(onSnooze, Color.Transparent, ink, 76.dp, shape, Modifier.fillMaxWidth(), border = BorderStroke(2.dp, ink)) {
                    PaperButtonRow(stringResource(R.string.ringing_snooze)) {
                        state.snoozeMinutes?.let { Text(pluralStringResource(R.plurals.ringing_snooze_plus, it, it), style = RingType.paperButtonDetail) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperCell(title: String, value: String, valueStyle: TextStyle, modifier: Modifier) {
    Column(modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(rememberLabels().formats.locale), style = RingType.paperCellTitle, color = RingColors.PaperSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = valueStyle, color = RingColors.PaperInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PaperButtonRow(label: String, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = RingType.paperButton)
        detail()
    }
}

/**
 * The sample alarm shown by the picker's thumbnails and by preview mode: [label] ("Work") in the first policy colour,
 * Mon–Wed, next tomorrow, a 9 min snooze, a 5 min auto-snooze, rung for 0:42 (the mockups' renderVals()).
 */
fun sampleRingState(label: String, now: ZonedDateTime): RingState = RingState(
    now = now,
    title = label,
    policyId = SAMPLE_POLICY_ID,
    policy = PolicyRingInfo(label, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), now.plusDays(1).toInstant()),
    backupOf = now.toInstant(),
    snoozeMinutes = 9,
    autoSnoozeMinutes = 5,
    ringingFor = Duration.ofSeconds(42),
)

/** Any id in the first policy-colour slot (#8AB4FF, as in RingPolicy.dc.html). */
private const val SAMPLE_POLICY_ID = 0L
