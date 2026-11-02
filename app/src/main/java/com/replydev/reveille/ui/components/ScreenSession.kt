package com.replydev.reveille.ui.components

import android.app.Application
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replydev.reveille.container
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A snackbar to show; [onAction] runs if the user taps [actionLabel]. */
class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

/**
 * Per-screen state that must outlive the screen's composition: writes in flight, their results and the id being
 * edited. Lives as long as the navigation entry (so it survives rotation and theme changes); [editId] and [saved]
 * also survive process death through [SavedStateHandle].
 *
 * Writes run in the app scope, never in a composition scope: leaving the screen mid-save must not cancel the save
 * or its re-arm. Results come back through [viewModelScope], so they are dropped once the screen is gone for good.
 */
class ScreenSession(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    private val appScope = application.container.appScope

    val messages = Channel<UiMessage>(Channel.BUFFERED)

    /** True while a guarded write runs; editors disable Save and Delete meanwhile. */
    var busy by mutableStateOf(false)
        private set

    private var editIdState by mutableLongStateOf(handle.get<Long>(EDIT_ID) ?: handle.get<Long>(NAV_ID) ?: 0L)

    /** The edited item's id: the navigation argument, 0 for a new item, then the id the first save returned. */
    var editId: Long
        get() = editIdState
        set(value) {
            editIdState = value
            handle[EDIT_ID] = value
        }

    private var savedState by mutableStateOf(handle.get<Boolean>(SAVED) ?: false)

    /** Set once the editor's work is done; the editor closes the next time it is resumed. */
    var saved: Boolean
        get() = savedState
        set(value) {
            savedState = value
            handle[SAVED] = value
        }

    fun message(message: UiMessage) {
        messages.trySend(message)
    }

    /**
     * Runs [action] in the app scope. A [guarded] write is ignored while another guarded write runs, so a double tap
     * cannot save twice. On failure the user sees [failure]; on success [onSuccess] runs on the main thread.
     */
    fun <T> write(failure: String, guarded: Boolean = true, action: suspend () -> T, onSuccess: (T) -> Unit = {}) {
        if (guarded) {
            if (busy) return
            busy = true
        }
        appScope.launch {
            val result = try {
                Result.success(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Reveille", failure, e)
                Result.failure(e)
            }
            viewModelScope.launch {
                if (guarded) busy = false
                result.fold(onSuccess) { message(UiMessage(failure)) }
            }
        }
    }

    private companion object {
        const val NAV_ID = "id"
        const val EDIT_ID = "session_edit_id"
        const val SAVED = "session_saved"
    }
}

/** The [ScreenSession] of the current navigation entry; its messages are shown in [snackbar]. */
@Composable
fun rememberScreenSession(snackbar: SnackbarHostState): ScreenSession {
    val session = viewModel<ScreenSession>()
    LaunchedEffect(session, snackbar) {
        for (message in session.messages) {
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.actionLabel,
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
    return session
}

/** Calls [onDone] once [ScreenSession.saved] is set and the screen is resumed, also after a save finished in the background. */
@Composable
fun LeaveWhenSaved(session: ScreenSession, onDone: () -> Unit) {
    if (!session.saved) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { onDone() }
    }
}
