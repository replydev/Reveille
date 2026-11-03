package com.replydev.reveille.update

import com.replydev.reveille.engine.Armed
import com.replydev.reveille.update.UpdateUiState.AlarmSoon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val release = TestReleases.release("v0.3.0")
    private val work = Armed("P:1:1:2026-11-04", Instant.parse("2026-11-04T06:55:00Z"), "Work")

    private var guard: GuardResult = GuardResult.Allowed
    private var canInstall = true
    private var verifyResult: VerifyResult = VerifyResult.Ok
    private val installed = mutableListOf<File>()
    private val downloader = FakeDownloader()

    private val controller by lazy {
        UpdateController(
            scope = scope,
            downloader = downloader,
            verify = { _, _ -> verifyResult },
            installer = object : ApkInstaller {
                override fun canRequestInstalls() = canInstall
                override fun install(apk: File) {
                    installed += apk
                }
            },
            guard = { guard },
        )
    }

    private inner class FakeDownloader : ApkDownloader {
        var result: DownloadResult? = null
        var gate: CompletableDeferred<Unit>? = null
        var cleared = 0

        override suspend fun download(release: Release, onProgress: (Float) -> Unit): DownloadResult {
            onProgress(0.5f)
            gate?.await()
            return result ?: DownloadResult.Ok(tmp.newFile("reveille-${release.tag}.apk"))
        }

        override fun clear() {
            cleared++
        }
    }

    private val state get() = controller.state.value

    @Test
    fun downloadVerifyThenInstall() {
        controller.show(release)
        assertEquals(UpdateUiState.Idle(release), state)
        controller.start()
        assertEquals(UpdateUiState.Ready, state)
        controller.install()
        assertEquals(UpdateUiState.Installing, state)
        assertEquals(1, installed.size)
    }

    @Test
    fun progressIsShownWhileDownloading() {
        downloader.gate = CompletableDeferred()
        controller.show(release)
        controller.start()
        assertEquals(UpdateUiState.Downloading(0.5f), state)
        downloader.gate!!.complete(Unit)
        scope.testScheduler.runCurrent()
        assertEquals(UpdateUiState.Ready, state)
    }

    @Test
    fun cancellingTheDownloadGoesBackToIdle() {
        downloader.gate = CompletableDeferred()
        controller.show(release)
        controller.start()
        controller.cancel()
        assertEquals(UpdateUiState.Idle(release), state)
        downloader.gate!!.complete(Unit)
        scope.testScheduler.runCurrent()
        assertEquals(UpdateUiState.Idle(release), state)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun aChecksumFailureInstallsNothing() {
        downloader.result = DownloadResult.Failed(FailReason.CHECKSUM)
        controller.show(release)
        controller.start()
        assertEquals(UpdateUiState.Failed(FailReason.CHECKSUM), state)
        controller.install()
        assertTrue(installed.isEmpty())
    }

    @Test
    fun aSignatureFailureDeletesTheFileAndInstallsNothing() {
        verifyResult = VerifyResult.Failed(FailReason.SIGNATURE)
        controller.show(release)
        controller.start()
        assertEquals(UpdateUiState.Failed(FailReason.SIGNATURE), state)
        assertTrue(downloader.cleared > 0)
        controller.install()
        assertTrue(installed.isEmpty())
    }

    @Test
    fun withoutThePermissionAskThenContinueOnResume() {
        canInstall = false
        controller.show(release)
        controller.start()
        assertEquals(UpdateUiState.NeedsPermission, state)
        controller.permissionMaybeChanged()
        assertEquals(UpdateUiState.NeedsPermission, state)
        canInstall = true
        controller.permissionMaybeChanged()
        assertEquals(UpdateUiState.Ready, state)
    }

    @Test
    fun theGuardHoldsBothTheDownloadAndTheInstall() {
        guard = GuardResult.AlarmSoon(work)
        controller.show(release)
        controller.start()
        assertEquals(AlarmSoon("Work", work.at, ringing = false, then = AlarmSoon.Then.DOWNLOAD), state)
        controller.installAnyway()
        assertEquals(UpdateUiState.Ready, state)
        // Overridden once for this release: Install doesn't ask again for the same alarm.
        controller.install()
        assertEquals(UpdateUiState.Installing, state)
    }

    @Test
    fun aRingingAlarmCannotBeOverridden() {
        controller.show(release)
        controller.start()
        guard = GuardResult.Ringing(work)
        controller.install()
        assertEquals(AlarmSoon("Work", work.at, ringing = true, then = AlarmSoon.Then.INSTALL), state)
        controller.installAnyway()
        assertTrue(installed.isEmpty())
        // Even after an earlier override.
        guard = GuardResult.AlarmSoon(work)
        controller.refreshGuard()
        controller.installAnyway()
        assertEquals(UpdateUiState.Installing, state)
        guard = GuardResult.Ringing(work)
        controller.installResult(InstallResult.Cancelled)
        controller.install()
        assertEquals(AlarmSoon("Work", work.at, ringing = true, then = AlarmSoon.Then.INSTALL), state)
        assertEquals(1, installed.size)
    }

    @Test
    fun installerResults() {
        controller.show(release)
        controller.start()
        controller.install()
        controller.installResult(InstallResult.Cancelled)
        assertEquals(UpdateUiState.Ready, state)
        controller.install()
        controller.installResult(InstallResult.Failed(UpdateFailure.STORAGE))
        assertEquals(UpdateUiState.Failed(FailReason.INSTALL, UpdateFailure.STORAGE), state)
    }

    @Test
    fun anInstallThatThrowsIsAFailureNotACrash() {
        val throwing = UpdateController(
            scope, downloader, { _, _ -> VerifyResult.Ok },
            object : ApkInstaller {
                override fun canRequestInstalls() = true
                override fun install(apk: File) = throw SecurityException("no")
            },
            { GuardResult.Allowed },
        )
        throwing.show(release)
        throwing.start()
        throwing.install()
        assertEquals(UpdateUiState.Failed(FailReason.INSTALL, UpdateFailure.UNKNOWN), throwing.state.value)
    }

    @Test
    fun showingANewerReleaseResetsAFinishedFlowButNotOneInProgress() {
        downloader.result = DownloadResult.Failed(FailReason.DOWNLOAD)
        controller.show(release)
        controller.start()
        controller.show(release)
        assertEquals(UpdateUiState.Failed(FailReason.DOWNLOAD), state)
        val v040 = TestReleases.release("v0.4.0")
        controller.show(v040)
        assertEquals(UpdateUiState.Idle(v040), state)

        downloader.result = null
        controller.start()
        assertEquals(UpdateUiState.Ready, state)
        controller.show(release)
        assertEquals(UpdateUiState.Ready, state)
        assertFalse(installed.isNotEmpty())
    }
}
