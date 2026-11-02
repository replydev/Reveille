package com.replydev.reveille.update

import java.time.Instant

enum class FailReason(val title: String) {
    CHECKSUM("Checksum didn't match"),
    SIGNATURE("Signature didn't match"),

    /** Not our package, or not the newer version the release promised. */
    PACKAGE("Package didn't match"),
    DOWNLOAD("Download failed"),
    INSTALL("Install failed"),
}

/** Drives the release sheet's footer (`UpdateStates.dc.html`). */
sealed interface UpdateUiState {
    data class Idle(val release: Release) : UpdateUiState

    /** [progress] in 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : UpdateUiState

    data object Verifying : UpdateUiState

    data object Ready : UpdateUiState

    data object NeedsPermission : UpdateUiState

    /**
     * The guard stopped us. [then] is where "Install anyway" continues; it does nothing while [ringing].
     */
    data class AlarmSoon(val label: String, val at: Instant, val ringing: Boolean, val then: Then) : UpdateUiState {
        enum class Then { DOWNLOAD, INSTALL }
    }

    data class Failed(val reason: FailReason, val detail: String? = null) : UpdateUiState

    /** Android's installer is on screen. */
    data object Installing : UpdateUiState

    companion object {
        /** The whole state machine. Events that don't apply to the current state leave it unchanged. */
        fun reduce(release: Release, state: UpdateUiState, event: UpdateEvent): UpdateUiState = when (event) {
            is UpdateEvent.Start -> when (state) {
                is Idle, is Failed -> guarded(event.guard, AlarmSoon.Then.DOWNLOAD) ?: Downloading(null)
                else -> state
            }
            UpdateEvent.InstallAnyway -> when {
                state !is AlarmSoon || state.ringing -> state
                state.then == AlarmSoon.Then.DOWNLOAD -> Downloading(null)
                else -> Installing
            }
            is UpdateEvent.GuardChanged -> when {
                state !is AlarmSoon -> state
                event.guard == GuardResult.Allowed -> if (state.then == AlarmSoon.Then.DOWNLOAD) Idle(release) else Ready
                else -> guarded(event.guard, state.then) ?: state
            }
            is UpdateEvent.Progress -> if (state is Downloading) Downloading(event.fraction) else state
            UpdateEvent.Downloaded -> if (state is Downloading) Verifying else state
            is UpdateEvent.Verified -> when (state) {
                Verifying -> if (event.canInstall) Ready else NeedsPermission
                else -> state
            }
            is UpdateEvent.PermissionChecked -> if (state == NeedsPermission && event.granted) Ready else state
            is UpdateEvent.Install -> when (state) {
                Ready -> guarded(event.guard, AlarmSoon.Then.INSTALL) ?: Installing
                else -> state
            }
            UpdateEvent.InstallNeedsPermission -> if (state == Installing) NeedsPermission else state
            UpdateEvent.InstallCancelled -> if (state == Installing) Ready else state
            UpdateEvent.Cancel -> if (state is Downloading) Idle(release) else state
            is UpdateEvent.Fail -> when (state) {
                is Downloading, Verifying, Installing -> Failed(event.reason, event.detail)
                else -> state
            }
        }

        private fun guarded(guard: GuardResult, then: AlarmSoon.Then): AlarmSoon? = when (guard) {
            GuardResult.Allowed -> null
            is GuardResult.AlarmSoon -> AlarmSoon(guard.armed.label, guard.armed.at, ringing = false, then = then)
            is GuardResult.Ringing -> AlarmSoon(guard.armed.label, guard.armed.at, ringing = true, then = then)
        }
    }
}

sealed interface UpdateEvent {
    /** "Download and install", or "Try again" after a failure. */
    data class Start(val guard: GuardResult) : UpdateEvent

    data object InstallAnyway : UpdateEvent

    /** The alarm behind an [UpdateUiState.AlarmSoon] card changed (started ringing, or has rung). */
    data class GuardChanged(val guard: GuardResult) : UpdateEvent

    data class Progress(val fraction: Float?) : UpdateEvent

    data object Downloaded : UpdateEvent

    data class Verified(val canInstall: Boolean) : UpdateEvent

    /** Back from the "Install unknown apps" settings screen. */
    data class PermissionChecked(val granted: Boolean) : UpdateEvent

    /** "Install" on the verified card. */
    data class Install(val guard: GuardResult) : UpdateEvent

    data object InstallNeedsPermission : UpdateEvent

    data object InstallCancelled : UpdateEvent

    /** "Cancel" while downloading. */
    data object Cancel : UpdateEvent

    data class Fail(val reason: FailReason, val detail: String? = null) : UpdateEvent
}
