package com.replydev.reveille.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.replydev.reveille.container
import kotlin.coroutines.cancellation.CancellationException

/** Result of reading the reliability checks when they could not be computed. */
const val CHECKS_FAILED = -1

/**
 * How many reliability checks fail: null while unknown, [CHECKS_FAILED] if they could not run. Shared by the Health
 * badge in the nav bar and the Today pill. Re-read whenever the caller's lifecycle resumes (back from a settings
 * screen) and whenever a manual check is confirmed or unconfirmed.
 */
@Composable
fun rememberFailingChecks(): Int? {
    val container = LocalContext.current.container
    // Saveable so that coming back shows the last result at once instead of popping the pill in after the transition.
    var failing by rememberSaveable { mutableStateOf<Int?>(null) }
    val confirmed by container.settings.confirmedChecks.collectAsStateWithLifecycle(initialValue = null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, confirmed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            failing = try {
                container.reliability.checks().count { !it.ok }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Reveille", "Reliability check failed", e)
                CHECKS_FAILED
            }
        }
    }
    return failing
}
