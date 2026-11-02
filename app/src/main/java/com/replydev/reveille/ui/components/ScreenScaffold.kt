package com.replydev.reveille.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        content = content,
    )
}

/**
 * A top-level screen: below the status bar, above the [ReveilleNavBar] (which the nav host draws on top). The FAB
 * sits 16 dp above the bar and the snackbar above the FAB.
 */
@Composable
fun TabScaffold(
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = ReveilleTheme.colors.bg,
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = { NavBarSpacer() },
        floatingActionButton = floatingActionButton,
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        content = content,
    )
}

/** The header of a top-level screen: an optional [overline] line above the 28 sp [title], and a [trailing] slot. */
@Composable
fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    bottom: Dp = 12.dp,
    trailing: @Composable (() -> Unit)? = null,
    below: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                overline?.let { Text(it, style = ReveilleTheme.type.caption, color = ReveilleTheme.colors.muted) }
                Text(title, style = ReveilleTheme.type.titleL, color = ReveilleTheme.colors.onBg)
            }
            trailing?.invoke()
        }
        below?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 8.dp))
}

/** Content padding for a LazyColumn inside a scaffold: keeps room for the FAB at the bottom. */
fun listPadding(scaffold: PaddingValues, fabRows: Int = 1, horizontal: Dp = 16.dp): PaddingValues = PaddingValues(
    start = horizontal,
    end = horizontal,
    top = scaffold.calculateTopPadding() + 8.dp,
    bottom = scaffold.calculateBottomPadding() + 32.dp + 68.dp * fabRows,
)

/** Today's date, kept current across midnight while the screen is shown. */
@Composable
fun rememberToday(): LocalDate {
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(30_000)
            value = LocalDate.now()
        }
    }
    return today
}
