package com.replydev.reveille.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.replydev.reveille.text.Labels

/**
 * [Labels] for the current language and clock setting; recreated on configuration change and on resume. The
 * 12/24-hour setting isn't part of the configuration, so coming back from system Settings re-reads it.
 */
@Composable
fun rememberLabels(): Labels {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(configuration, state.isAtLeast(Lifecycle.State.RESUMED)) { Labels.of(context) }
}
