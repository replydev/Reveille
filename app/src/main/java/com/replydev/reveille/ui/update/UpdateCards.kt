package com.replydev.reveille.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.update.CheckOutcome
import com.replydev.reveille.update.FetchError
import com.replydev.reveille.update.GitHubReleaseSource
import com.replydev.reveille.update.Release
import com.replydev.reveille.update.ReleaseNotes
import com.replydev.reveille.update.UpdatePrefs
import com.replydev.reveille.update.UpdateText
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.components.Pill
import com.replydev.reveille.ui.components.IconCircleButton
import com.replydev.reveille.ui.components.Overline
import com.replydev.reveille.ui.components.ReveilleCard
import com.replydev.reveille.ui.components.ReveilleIcon
import com.replydev.reveille.ui.components.ReveilleSwitch
import com.replydev.reveille.ui.components.topDivider
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Today's slim update card (`UpdateToday.dc.html`). The caller decides when it may show (see `HomeScreen`). */
@Composable
fun UpdateNotice(release: Release, onView: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReveilleTheme.colors
    val headline = remember(release.notes) { ReleaseNotes.parse(release.notes).headline }
    ReveilleCard(
        modifier.fillMaxWidth(),
        onClick = onView,
        color = colors.accentTint,
        border = colors.accentLine,
        padding = PaddingValues(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_download, colors.accentContainer, colors.accent, size = 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.update_ready, release.versionName), style = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold))
                if (headline != null) {
                    Text(headline, style = ReveilleTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(stringResource(R.string.update_view), Modifier.padding(horizontal = 6.dp), style = ReveilleTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.accent)
            IconCircleButton(R.drawable.ic_close, stringResource(R.string.update_dismiss), onDismiss, container = Color.Transparent)
        }
    }
}

/** Settings › Updates (`UpdateSettings.dc.html`): status row, the two switches, Last checked, and the footnote. */
@Composable
fun UpdatesSection(prefs: UpdatePrefs, onView: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val updates = context.container.updates
    val scope = rememberCoroutineScope()
    val colors = ReveilleTheme.colors
    val type = ReveilleTheme.type
    val zone = ZoneId.systemDefault()
    val labels = rememberLabels()
    val text = remember(labels) { UpdateText(labels.res, labels) }
    var checking by remember { mutableStateOf(false) }
    val now by produceState(Instant.now(), prefs.lastCheckedAt) {
        while (true) {
            value = Instant.now()
            delay(60_000)
        }
    }
    val available = prefs.available(updates.installedVersionCode)
    val write: (suspend () -> Unit) -> Unit = { action ->
        context.container.appScope.launch {
            try {
                action()
            } catch (e: Exception) {
                Log.e("Reveille", "Could not save the update setting", e)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(stringResource(R.string.update_section), Modifier.padding(horizontal = 4.dp))
        ReveilleCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
            when {
                available != null -> StatusRow(
                    tile = { IconTile(R.drawable.ic_download, colors.accent, colors.onAccent) },
                    title = stringResource(R.string.update_available_title, available.versionName),
                    subtitle = stringResource(
                        R.string.update_available_subtitle,
                        updates.installedVersionName,
                        text.released(available.publishedAt, zone),
                    ),
                    background = colors.accentTint,
                    onClick = onView,
                ) {
                    Pill(onClick = onView, container = colors.accent, content = colors.onAccent, height = 36.dp, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text(stringResource(R.string.update_view), style = type.caption.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
                !updates.selfUpdate -> StatusRow(
                    tile = { IconTile(R.drawable.ic_download, colors.surfaceHigh, colors.muted) },
                    title = stringResource(R.string.update_debug_title),
                    subtitle = stringResource(R.string.update_debug_subtitle, updates.installedVersionName),
                )
                else -> StatusRow(
                    tile = { IconTile(R.drawable.ic_check, colors.surfaceHigh, colors.success) },
                    title = stringResource(R.string.update_up_to_date_title),
                    subtitle = stringResource(R.string.update_version, updates.installedVersionName),
                )
            }
            SettingSwitch(
                stringResource(R.string.update_auto_check),
                stringResource(R.string.update_auto_check_hint),
                checked = prefs.autoCheck && updates.selfUpdate,
                enabled = updates.selfUpdate,
            ) { value ->
                write {
                    updates.store.setAutoCheck(value)
                    updates.syncSchedule()
                }
            }
            SettingSwitch(stringResource(R.string.update_notify), stringResource(R.string.update_notify_hint), prefs.notify) { value ->
                write { updates.store.setNotify(value) }
            }
            Row(
                Modifier.fillMaxWidth().topDivider(colors.outline).heightIn(min = 60.dp).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.update_last_checked), style = type.bodyL)
                    Text(text.lastChecked(prefs.lastCheckedAt, now, zone), style = type.caption, color = colors.muted)
                }
                Pill(
                    onClick = {
                        if (checking) return@Pill
                        checking = true
                        scope.launch {
                            val outcome = try {
                                updates.checker.check(manual = true)
                            } finally {
                                checking = false
                            }
                            checkMessage(outcome)?.let { onMessage(labels.res.getString(it)) }
                        }
                    },
                    container = Color.Transparent,
                    content = colors.onBg,
                    border = BorderStroke(1.dp, colors.outlineStrong),
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = colors.muted, strokeWidth = 2.dp, trackColor = Color.Transparent)
                        } else {
                            ReveilleIcon(R.drawable.ic_refresh, null, tint = colors.onBg, size = 16.dp)
                        }
                        Text(stringResource(if (checking) R.string.update_checking else R.string.update_check_now), style = type.body.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
        val repo = "github.com/replydev/Reveille"
        val footnote = stringResource(R.string.update_footnote, repo)
        Text(
            buildAnnotatedString {
                append(footnote)
                val start = footnote.indexOf(repo)
                if (start >= 0) addStyle(SpanStyle(fontFamily = type.mono.fontFamily, color = colors.onBg), start, start + repo.length)
            },
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
            style = type.paragraphSmall,
            color = colors.muted,
        )
    }
}

/** Settings › About: the Daybreak mark, the version, and the current release's notes on GitHub. */
@Composable
fun AboutRow(versionName: String) {
    val context = LocalContext.current
    val colors = ReveilleTheme.colors
    val url = if (versionName.endsWith("-dev")) {
        "${GitHubReleaseSource.REPO_URL}/releases"
    } else {
        "${GitHubReleaseSource.REPO_URL}/releases/tag/v$versionName"
    }
    ReveilleCard(Modifier.fillMaxWidth(), onClick = { openUrl(context, url) }, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DaybreakTile(40.dp, radius = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_name), style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
                Text(stringResource(R.string.update_version, versionName), style = ReveilleTheme.type.mono, color = colors.muted)
            }
            Text(stringResource(R.string.settings_release_notes), style = ReveilleTheme.type.caption, color = colors.muted)
        }
    }
}

@Composable
private fun StatusRow(
    tile: @Composable () -> Unit,
    title: String,
    subtitle: String,
    background: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        tile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ReveilleTheme.type.bodyL.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
        }
        trailing()
    }
}

@Composable
private fun IconTile(@DrawableRes icon: Int, container: Color, tint: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).background(container, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        ReveilleIcon(icon, null, tint = tint, size = 20.dp)
    }
}

/** The whole row toggles; the switch only shows the state. */
@Composable
private fun SettingSwitch(label: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .topDivider(ReveilleTheme.colors.outline)
            .heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = ReveilleTheme.type.bodyL)
            Text(hint, style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted)
        }
        ReveilleSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The snackbar after "Check now", or null when the status row already says it all. */
@StringRes
fun checkMessage(outcome: CheckOutcome): Int? = when (outcome) {
    CheckOutcome.UpToDate -> R.string.update_message_up_to_date
    is CheckOutcome.Available, CheckOutcome.Disabled -> null
    is CheckOutcome.Failed -> when (outcome.error) {
        FetchError.Offline -> R.string.update_message_offline
        is FetchError.RateLimited, is FetchError.Http -> R.string.update_message_busy
        FetchError.Malformed -> R.string.update_message_malformed
    }
}

fun openUrl(context: Context, url: String) = startActivitySafely(context, Intent(Intent.ACTION_VIEW, url.toUri()))

fun startActivitySafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("Reveille", "Nothing can open $intent", e)
    }
}
