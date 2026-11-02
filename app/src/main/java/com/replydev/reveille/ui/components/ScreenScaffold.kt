package com.replydev.reveille.ui.components

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

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

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/** Content padding for a LazyColumn inside [ScreenScaffold]: keeps room for the FAB at the bottom. */
fun listPadding(scaffold: PaddingValues): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = scaffold.calculateTopPadding() + 8.dp,
    bottom = scaffold.calculateBottomPadding() + 96.dp,
)

/**
 * Runs a schedule write from a screen. A failure (database, re-arm) is logged and shown in the snackbar instead of
 * crashing the app; returns whether [action] completed.
 */
suspend fun SnackbarHostState.runReporting(failureMessage: String, action: suspend () -> Unit): Boolean =
    try {
        action()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("Reveille", failureMessage, e)
        showSnackbar(failureMessage)
        false
    }
