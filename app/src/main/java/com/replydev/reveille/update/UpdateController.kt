package com.replydev.reveille.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

interface ApkDownloader {
    suspend fun download(release: Release, onProgress: (Float) -> Unit): DownloadResult

    fun clear()
}

interface ApkInstaller {
    fun canRequestInstalls(): Boolean

    /** Commits the install; the outcome comes back through [UpdateController.installResult]. */
    fun install(apk: File)
}

/**
 * Runs the release sheet's flow in the app scope, so a download carries on when the sheet is closed and the sheet
 * finds it again when reopened. Every transition goes through [UpdateUiState.reduce]; this class only performs the
 * side effects the new state calls for.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val downloader: ApkDownloader,
    private val verify: (File, Int) -> VerifyResult,
    private val installer: ApkInstaller,
    private val guard: suspend () -> GuardResult,
) {
    private val _state = MutableStateFlow<UpdateUiState?>(null)

    /** Null until a release has been shown. */
    val state: StateFlow<UpdateUiState?> = _state.asStateFlow()

    private var release: Release? = null
    private var apk: File? = null
    private var download: Job? = null

    /** "Install anyway" was tapped for this release: the 30-minute guard doesn't ask again. Ringing still blocks. */
    private var overridden = false

    /** Opens the flow for [release]. A flow in progress is left alone; a finished one restarts for a new release. */
    @Synchronized
    fun show(release: Release) {
        val current = _state.value
        val restart = current == null ||
            ((current is UpdateUiState.Idle || current is UpdateUiState.Failed) && release != this.release)
        if (!restart) return
        this.release = release
        overridden = false
        _state.value = UpdateUiState.Idle(release)
    }

    /** "Download and install" or "Try again". */
    fun start() {
        scope.launch { if (dispatch(UpdateEvent.Start(effectiveGuard())) is UpdateUiState.Downloading) runDownload() }
    }

    fun installAnyway() {
        val before = _state.value
        if (before !is UpdateUiState.AlarmSoon || before.ringing) return
        overridden = true
        when (dispatch(UpdateEvent.InstallAnyway)) {
            is UpdateUiState.Downloading -> runDownload()
            UpdateUiState.Installing -> runInstall()
            else -> Unit
        }
    }

    /** "Install" on the verified card: the guard is checked again, time has passed since the download. */
    fun install() {
        scope.launch { if (dispatch(UpdateEvent.Install(effectiveGuard())) == UpdateUiState.Installing) runInstall() }
    }

    fun cancel() {
        download?.cancel()
        download = null
        dispatch(UpdateEvent.Cancel)
    }

    /** Back from the "Install unknown apps" screen (or any resume while asking for it). */
    fun permissionMaybeChanged() {
        dispatch(UpdateEvent.PermissionChecked(installer.canRequestInstalls()))
    }

    /** Re-reads the alarm behind a guard card: it may have started ringing, or have rung. */
    fun refreshGuard() {
        if (_state.value !is UpdateUiState.AlarmSoon) return
        scope.launch { dispatch(UpdateEvent.GuardChanged(guard())) }
    }

    fun installResult(result: InstallResult) {
        when (result) {
            // The process is replaced right after; nothing to show.
            InstallResult.Success -> apk = null
            // Android's dialog is up; the state stays Installing until it answers.
            is InstallResult.NeedsUserAction -> Unit
            InstallResult.Cancelled -> dispatch(UpdateEvent.InstallCancelled)
            is InstallResult.Failed -> dispatch(UpdateEvent.Fail(FailReason.INSTALL, result.failure))
        }
    }

    private suspend fun effectiveGuard(): GuardResult {
        val result = guard()
        return if (overridden && result is GuardResult.AlarmSoon) GuardResult.Allowed else result
    }

    private fun runDownload() {
        val release = release ?: return
        download = scope.launch {
            val result = downloader.download(release) { dispatch(UpdateEvent.Progress(it)) }
            // Cancelled meanwhile: Cancel already moved the state back.
            if (_state.value !is UpdateUiState.Downloading) {
                if (result is DownloadResult.Ok) downloader.clear()
                return@launch
            }
            when (result) {
                is DownloadResult.Failed -> dispatch(UpdateEvent.Fail(result.reason))
                is DownloadResult.Ok -> {
                    dispatch(UpdateEvent.Downloaded)
                    when (val verified = verify(result.file, release.versionCode)) {
                        VerifyResult.Ok -> {
                            apk = result.file
                            dispatch(UpdateEvent.Verified(installer.canRequestInstalls()))
                        }
                        is VerifyResult.Failed -> {
                            downloader.clear()
                            dispatch(UpdateEvent.Fail(verified.reason))
                        }
                    }
                }
            }
        }
    }

    private fun runInstall() {
        val file = apk?.takeIf { it.exists() }
        if (file == null) {
            dispatch(UpdateEvent.Fail(FailReason.DOWNLOAD))
            return
        }
        try {
            installer.install(file)
        } catch (e: Exception) {
            // E.g. the session could not be created or written (storage, a revoked permission). The installer logs the
            // message; the sheet only shows a translated sentence.
            dispatch(UpdateEvent.Fail(FailReason.INSTALL, UpdateFailure.UNKNOWN))
        }
    }

    @Synchronized
    private fun dispatch(event: UpdateEvent): UpdateUiState? {
        val release = release ?: return null
        val current = _state.value ?: return null
        val next = UpdateUiState.reduce(release, current, event)
        _state.value = next
        return next
    }
}
