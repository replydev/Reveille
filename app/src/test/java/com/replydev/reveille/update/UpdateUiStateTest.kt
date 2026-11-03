package com.replydev.reveille.update

import com.replydev.reveille.engine.Armed
import com.replydev.reveille.update.UpdateEvent.Cancel
import com.replydev.reveille.update.UpdateEvent.Downloaded
import com.replydev.reveille.update.UpdateEvent.Fail
import com.replydev.reveille.update.UpdateEvent.GuardChanged
import com.replydev.reveille.update.UpdateEvent.Install
import com.replydev.reveille.update.UpdateEvent.InstallAnyway
import com.replydev.reveille.update.UpdateEvent.InstallCancelled
import com.replydev.reveille.update.UpdateEvent.PermissionChecked
import com.replydev.reveille.update.UpdateEvent.Progress
import com.replydev.reveille.update.UpdateEvent.Start
import com.replydev.reveille.update.UpdateEvent.Verified
import com.replydev.reveille.update.UpdateUiState.AlarmSoon
import com.replydev.reveille.update.UpdateUiState.AlarmSoon.Then
import com.replydev.reveille.update.UpdateUiState.Downloading
import com.replydev.reveille.update.UpdateUiState.Failed
import com.replydev.reveille.update.UpdateUiState.Idle
import com.replydev.reveille.update.UpdateUiState.Installing
import com.replydev.reveille.update.UpdateUiState.NeedsPermission
import com.replydev.reveille.update.UpdateUiState.Ready
import com.replydev.reveille.update.UpdateUiState.Verifying
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class UpdateUiStateTest {
    private val release = TestReleases.release("v0.3.0")
    private val idle = Idle(release)
    private val work = Armed("P:1:1:2026-11-04", Instant.parse("2026-11-04T06:55:00Z"), "Work")
    private val soon = GuardResult.AlarmSoon(work)
    private val ringing = GuardResult.Ringing(work)

    private fun UpdateUiState.on(event: UpdateEvent) = UpdateUiState.reduce(release, this, event)

    @Test
    fun theHappyPath() {
        val downloading = idle.on(Start(GuardResult.Allowed))
        assertEquals(Downloading(null), downloading)
        assertEquals(Downloading(0.62f), downloading.on(Progress(0.62f)))
        val verifying = downloading.on(Downloaded)
        assertEquals(Verifying, verifying)
        val ready = verifying.on(Verified(canInstall = true))
        assertEquals(Ready, ready)
        assertEquals(Installing, ready.on(Install(GuardResult.Allowed)))
    }

    @Test
    fun withoutThePermissionAskOnceThenContinue() {
        val needs = Verifying.on(Verified(canInstall = false))
        assertEquals(NeedsPermission, needs)
        assertEquals(NeedsPermission, needs.on(PermissionChecked(granted = false)))
        assertEquals(Ready, needs.on(PermissionChecked(granted = true)))
        // The installer can also report it (e.g. revoked between Ready and commit).
        assertEquals(NeedsPermission, Installing.on(UpdateEvent.InstallNeedsPermission))
    }

    @Test
    fun anAlarmSoonBlocksTheDownloadUntilOverridden() {
        val guarded = idle.on(Start(soon))
        assertEquals(AlarmSoon(work.label, work.at, ringing = false, then = Then.DOWNLOAD), guarded)
        assertEquals(Downloading(null), guarded.on(InstallAnyway))
    }

    @Test
    fun theGuardIsCheckedAgainAtInstallTime() {
        val guarded = Ready.on(Install(soon))
        assertEquals(AlarmSoon(work.label, work.at, ringing = false, then = Then.INSTALL), guarded)
        assertEquals(Installing, guarded.on(InstallAnyway))
    }

    @Test
    fun whileRingingInstallAnywayDoesNothing() {
        val fromIdle = idle.on(Start(ringing))
        assertEquals(AlarmSoon(work.label, work.at, ringing = true, then = Then.DOWNLOAD), fromIdle)
        assertEquals(fromIdle, fromIdle.on(InstallAnyway))
        val fromReady = Ready.on(Install(ringing))
        assertEquals(fromReady, fromReady.on(InstallAnyway))
    }

    @Test
    fun theGuardCardFollowsTheAlarm() {
        val guarded = idle.on(Start(soon))
        // It starts ringing while the sheet is open: Install anyway disappears.
        assertEquals(AlarmSoon(work.label, work.at, ringing = true, then = Then.DOWNLOAD), guarded.on(GuardChanged(ringing)))
        // It has rung: back to where the user was.
        assertEquals(idle, guarded.on(GuardChanged(GuardResult.Allowed)))
        assertEquals(Ready, Ready.on(Install(soon)).on(GuardChanged(GuardResult.Allowed)))
    }

    @Test
    fun failuresAndRetry() {
        assertEquals(Failed(FailReason.DOWNLOAD), Downloading(0.3f).on(Fail(FailReason.DOWNLOAD)))
        assertEquals(Failed(FailReason.CHECKSUM), Downloading(1f).on(Fail(FailReason.CHECKSUM)))
        assertEquals(Failed(FailReason.SIGNATURE), Verifying.on(Fail(FailReason.SIGNATURE)))
        assertEquals(Failed(FailReason.INSTALL, UpdateFailure.STORAGE), Installing.on(Fail(FailReason.INSTALL, UpdateFailure.STORAGE)))
        assertEquals(Downloading(null), Failed(FailReason.CHECKSUM).on(Start(GuardResult.Allowed)))
        assertEquals(AlarmSoon(work.label, work.at, false, Then.DOWNLOAD), Failed(FailReason.CHECKSUM).on(Start(soon)))
    }

    @Test
    fun cancellingTheDownloadOrTheInstallerGoesBack() {
        assertEquals(idle, Downloading(0.5f).on(Cancel))
        assertEquals(Ready, Installing.on(InstallCancelled))
    }

    @Test
    fun eventsThatDoNotApplyAreIgnored() {
        assertEquals(idle, idle.on(Downloaded))
        assertEquals(idle, idle.on(InstallAnyway))
        assertEquals(Ready, Ready.on(Progress(0.1f)))
        assertEquals(Verifying, Verifying.on(Cancel))
        assertEquals(Installing, Installing.on(Start(GuardResult.Allowed)))
        assertEquals(Downloading(null), Downloading(null).on(Start(GuardResult.Allowed)))
    }
}
