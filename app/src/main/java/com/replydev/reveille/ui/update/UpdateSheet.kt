package com.replydev.reveille.ui.update

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.update.ReleaseNotes
import com.replydev.reveille.update.UpdateText
import com.replydev.reveille.update.UpdateUiState
import com.replydev.reveille.ui.components.Dot
import com.replydev.reveille.ui.components.IconCircleButton
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.Pill
import com.replydev.reveille.ui.components.PillButton
import com.replydev.reveille.ui.components.PillStyle
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.TextAction
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The release sheet (`UpdateSheet.dc.html`): version, notes, the two trust lines, and a footer driven by
 * [UpdateUiState] (`UpdateStates.dc.html`). The flow itself lives in the app-scoped controller, so closing the sheet
 * never interrupts a download, and reopening it finds the current state.
 */
@Composable
fun UpdateSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val updates = context.container.updates
    val prefs by updates.store.prefs.collectAsStateWithLifecycle(initialValue = null)
    val state by updates.controller.state.collectAsStateWithLifecycle()
    val release = prefs?.available(updates.installedVersionCode)
    val zone = ZoneId.systemDefault()
    val scope = context.container.appScope
    val labels = rememberLabels()
    val text = remember(labels) { UpdateText(labels.res, labels) }

    LaunchedEffect(release) {
        // Opening the sheet answers the notification.
        updates.notifier.cancel()
        release?.let(updates.controller::show)
    }
    // Back from "Install unknown apps": the permission may have been granted.
    LifecycleResumeEffect(Unit) {
        updates.controller.permissionMaybeChanged()
        onPauseOrDispose { }
    }
    // A guard card follows its alarm: it may start ringing, or have rung.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state is UpdateUiState.AlarmSoon) {
        if (state !is UpdateUiState.AlarmSoon) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                updates.controller.refreshGuard()
                delay(5_000)
            }
        }
    }

    if (prefs != null && release == null) {
        // Installed meanwhile, or the stored release went away: nothing left to show.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    release ?: return
    val notes = remember(release.notes) { ReleaseNotes.parse(release.notes) }
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type

    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DaybreakTile(56.dp, radius = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline(stringResource(R.string.update_sheet_overline), color = colors.accent)
                Text(stringResource(R.string.update_version, release.versionName), style = type.headline.copy(fontWeight = FontWeight.SemiBold))
                Text(text.sheetSubtitle(release, updates.installedVersionName, zone), style = type.caption, color = colors.muted)
            }
            IconCircleButton(R.drawable.ic_close, stringResource(R.string.common_close), onClose)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            WhatsNew(notes, onGitHub = { openUrl(context, release.htmlUrl) })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrustLine(R.drawable.ic_shield_check, R.string.update_trust_verified, R.string.update_trust_verified_detail)
                TrustLine(R.drawable.ic_clock, R.string.update_trust_alarms, R.string.update_trust_alarms_detail)
            }
        }
        val skip = {
            scope.launch { updates.store.skip(release.versionCode) }
            onClose()
        }
        val skipLabel = stringResource(R.string.update_skip, release.versionName)
        if (!updates.selfUpdate) {
            // Debug builds are signed with another key: they can only point at the release.
            IdleFooter(stringResource(R.string.update_open_on_github), skipLabel, onPrimary = { openUrl(context, release.htmlUrl) }, onLater = onClose, onSkip = skip)
            return@Column
        }
        AnimatedContent(
            targetState = state ?: UpdateUiState.Idle(release),
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) },
            label = "footer",
        ) { footer ->
            when (footer) {
                is UpdateUiState.Idle -> IdleFooter(
                    stringResource(R.string.update_download_and_install),
                    skipLabel,
                    onPrimary = updates.controller::start,
                    onLater = onClose,
                    onSkip = skip,
                )
                is UpdateUiState.Downloading -> ProgressCard(footer.progress, onCancel = updates.controller::cancel)
                UpdateUiState.Verifying -> ProgressCard(null, onCancel = null)
                UpdateUiState.Ready, UpdateUiState.Installing -> ReadyCard(
                    installing = footer == UpdateUiState.Installing,
                    onInstall = updates.controller::install,
                )
                UpdateUiState.NeedsPermission -> PermissionCard(onOpenSettings = { startActivitySafely(context, updates.permissionIntent()) })
                is UpdateUiState.AlarmSoon -> AlarmSoonCard(
                    footer,
                    text = text,
                    zone = zone,
                    onRemind = {
                        scope.launch { updates.remindAfter(footer.at) }
                        onClose()
                    },
                    onInstallAnyway = updates.controller::installAnyway,
                )
                is UpdateUiState.Failed -> FailedCard(footer, onRetry = updates.controller::start)
            }
        }
    }
}

@Composable
private fun WhatsNew(notes: ReleaseNotes, onGitHub: () -> Unit) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    ReveilleCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(stringResource(R.string.update_whats_new), Modifier.weight(1f))
            TextAction(stringResource(R.string.update_on_github), onClick = onGitHub, color = colors.accent)
        }
        notes.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(section.kind.title), style = type.caption.copy(fontWeight = FontWeight.SemiBold))
                section.bullets.forEach { Bullet(it, if (section.kind.accent) colors.accent else colors.muted) }
                if (section.more > 0) Text(pluralStringResource(R.plurals.update_notes_more, section.more, section.more), style = type.caption, color = colors.muted)
            }
        }
        notes.fallback.forEach { Text(it, style = type.paragraph) }
        if (notes.sections.isEmpty() && notes.fallback.isEmpty()) {
            Text(stringResource(R.string.update_notes_none), style = type.paragraph, color = colors.muted)
        }
    }
}

@Composable
private fun Bullet(text: String, dot: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(dot, Modifier.padding(top = 8.dp), size = 6.dp)
        Text(text, style = ReveilleTheme.type.paragraph)
    }
}

@Composable
private fun TrustLine(@DrawableRes icon: Int, @StringRes lead: Int, @StringRes rest: Int) {
    val colors = ReveilleTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReveilleIcon(icon, null, Modifier.padding(top = 1.dp), tint = colors.success, size = 18.dp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.onBg)) { append(stringResource(lead)) }
                append(" ")
                append(stringResource(rest))
            },
            style = ReveilleTheme.type.paragraphSmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun IdleFooter(primary: String, skipLabel: String, onPrimary: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val colors = ReveilleTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(primary, onPrimary, Modifier.fillMaxWidth(), height = 56.dp, textStyle = ReveilleTheme.type.button)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FooterText(stringResource(R.string.update_later), colors.onBg, onLater, Modifier.weight(1f))
            FooterText(skipLabel, colors.muted, onSkip, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FooterText(text: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Pill(onClick = onClick, modifier = modifier, container = Color.Transparent, content = color, height = 48.dp) {
        Text(text, style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Downloading (determinate, with Cancel) or Verifying (indeterminate, no action). */
@Composable
private fun ProgressCard(progress: Float?, onCancel: (() -> Unit)?) {
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val verifying = onCancel == null
    ReveilleCard(Modifier.fillMaxWidth(), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (verifying) R.string.update_verifying else R.string.update_downloading), Modifier.weight(1f), style = type.bodyL.copy(fontWeight = FontWeight.SemiBold))
            if (!verifying && progress != null) Text(stringResource(R.string.update_percent, (progress * 100).toInt()), style = type.mono, color = colors.muted)
        }
        val barDescription = stringResource(if (verifying) R.string.update_verifying_description else R.string.update_download_progress)
        val barModifier = Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = barDescription }
        if (progress == null || verifying) {
            LinearProgressIndicator(barModifier, color = colors.accent, trackColor = colors.surfaceHigh, strokeCap = StrokeCap.Round, gapSize = 0.dp)
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = barModifier,
                color = colors.accent,
                trackColor = colors.surfaceHigh,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (verifying) R.string.update_verifying_detail else R.string.update_downloading_detail), Modifier.weight(1f), style = type.caption, color = colors.muted)
            if (onCancel != null) TextAction(stringResource(R.string.common_cancel), onCancel)
        }
    }
}

@Composable
private fun ReadyCard(installing: Boolean, onInstall: () -> Unit) {
    val colors = ReveilleTheme.colors
    ReveilleCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReveilleIcon(R.drawable.ic_shield_check, null, tint = colors.success, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.update_verified), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                Text(stringResource(R.string.update_verified_detail), style = ReveilleTheme.type.caption, color = colors.muted)
            }
            PillButton(
                stringResource(if (installing) R.string.update_installing else R.string.update_install),
                onInstall,
                height = 44.dp,
                enabled = !installing,
                textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                contentPadding = PaddingValues(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    NoticeCard(
        icon = R.drawable.ic_lock,
        iconTint = ReveilleTheme.colors.accent,
        title = stringResource(R.string.update_permission_title),
        body = stringResource(R.string.update_permission_body),
    ) {
        PillButton(stringResource(R.string.update_open_settings), onOpenSettings, style = PillStyle.Outlined, height = 40.dp, textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AlarmSoonCard(state: UpdateUiState.AlarmSoon, text: UpdateText, zone: ZoneId, onRemind: () -> Unit, onInstallAnyway: () -> Unit) {
    val colors = ReveilleTheme.colors
    val now by produceState(Instant.now(), state.at) {
        while (true) {
            value = Instant.now()
            delay(15_000)
        }
    }
    val title = if (state.ringing) stringResource(R.string.update_alarm_ringing, state.label) else text.ringsIn(state.label, state.at, now)
    NoticeCard(
        icon = R.drawable.ic_alarm_soon,
        iconTint = colors.accent,
        title = title,
        body = stringResource(R.string.update_alarm_soon_body, text.time(state.at, zone)),
        container = colors.accentTint,
        border = colors.accentLine,
    ) {
        Pill(onClick = onRemind, container = colors.accentContainer, content = colors.accent, height = 40.dp, contentPadding = PaddingValues(horizontal = 16.dp)) {
            Text(stringResource(R.string.update_remind_me), style = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold))
        }
        // Never offered while an alarm rings (spec §1.2).
        if (!state.ringing) TextAction(stringResource(R.string.update_install_anyway), onInstallAnyway, Modifier.height(40.dp))
    }
}

@Composable
private fun FailedCard(state: UpdateUiState.Failed, onRetry: () -> Unit) {
    val colors = ReveilleTheme.colors
    val body = stringResource(R.string.update_failed_body)
    val detail = state.detail?.let { stringResource(it.message) }
    NoticeCard(
        icon = R.drawable.ic_warning,
        iconTint = colors.dangerText,
        title = stringResource(state.reason.title),
        body = detail?.let { stringResource(R.string.update_failed_body_detail, it, body) } ?: body,
        container = colors.dangerContainer,
        border = colors.dangerLine,
        bodyColor = colors.dangerOnContainerMuted,
    ) {
        PillButton(stringResource(R.string.update_try_again), onRetry, style = PillStyle.Danger, height = 40.dp, textStyle = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Icon, title and body, with [actions] indented under the text (the states' shared layout). */
@Composable
private fun NoticeCard(
    @DrawableRes icon: Int,
    iconTint: Color,
    title: String,
    body: String,
    container: Color = ReveilleTheme.colors.surface,
    border: Color = ReveilleTheme.colors.outline,
    bodyColor: Color = ReveilleTheme.colors.muted,
    actions: @Composable () -> Unit,
) {
    ReveilleCard(Modifier.fillMaxWidth(), color = container, border = border, spacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReveilleIcon(icon, null, Modifier.padding(top = 1.dp), tint = iconTint, size = 20.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                Text(body, style = ReveilleTheme.type.paragraphSmall, color = bodyColor)
            }
        }
        Row(Modifier.padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

/** The Daybreak mark on a dark tile with an outline, as on the launcher. */
@Composable
fun DaybreakTile(size: Dp, radius: Dp) {
    val colors = ReveilleTheme.colors
    Surface(Modifier.size(size), shape = RoundedCornerShape(radius), color = colors.bg, border = BorderStroke(1.dp, colors.outline)) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}
