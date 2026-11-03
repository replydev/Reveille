package com.replydev.reveille.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val installer = UpdateInstaller(app)

    private fun status(code: Int, message: String? = null, confirm: Intent? = null) = Intent(UpdateInstaller.ACTION_STATUS)
        .putExtra(PackageInstaller.EXTRA_STATUS, code)
        .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
        .putExtra(Intent.EXTRA_INTENT, confirm)

    @Test
    fun thePermissionScreenIsOurUnknownSourcesEntry() {
        val intent = installer.permissionIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    fun installWritesTheApkIntoASessionForOurPackageAndCommitsIt() {
        val apk = tmp.newFile("reveille-v0.3.0.apk").apply { writeBytes(ByteArray(4_096) { it.toByte() }) }
        val results = mutableListOf<InstallResult>()
        val listener: (InstallResult) -> Unit = { results += it }
        UpdateInstaller.listeners += listener
        try {
            val id = installer.install(apk)
            val info = app.packageManager.packageInstaller.getSessionInfo(id)!!
            assertEquals(app.packageName, info.appPackageName)
            // Only a committed session reports back to our status receiver. (Robolectric sends that broadcast without
            // EXTRA_STATUS, so the status mapping itself is covered by statusesMapToResults.)
            shadowOf(app.packageManager.packageInstaller).setSessionSucceeds(id)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(results.isNotEmpty())
        } finally {
            UpdateInstaller.listeners -= listener
        }
    }

    @Test
    fun statusesMapToResults() {
        assertEquals(InstallResult.Success, UpdateInstaller.result(status(PackageInstaller.STATUS_SUCCESS)))
        assertEquals(InstallResult.Cancelled, UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_ABORTED)))
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val pending = UpdateInstaller.result(status(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm = confirm))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", (pending as InstallResult.NeedsUserAction).confirm.action)
        assertEquals(
            InstallResult.Failed(UpdateFailure.STORAGE),
            UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE")),
        )
        assertEquals(
            InstallResult.Failed(UpdateFailure.CONFLICT),
            UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_CONFLICT)),
        )
        assertEquals(
            InstallResult.Failed(UpdateFailure.BLOCKED),
            UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_BLOCKED)),
        )
        assertEquals(
            InstallResult.Failed(UpdateFailure.INCOMPATIBLE),
            UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_INVALID)),
        )
        assertEquals(
            InstallResult.Failed(UpdateFailure.INCOMPATIBLE),
            UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)),
        )
        assertEquals(InstallResult.Failed(UpdateFailure.UNKNOWN), UpdateInstaller.result(status(PackageInstaller.STATUS_FAILURE)))
        // A pending-user-action status without the confirm intent can't continue.
        assertTrue(UpdateInstaller.result(status(PackageInstaller.STATUS_PENDING_USER_ACTION)) is InstallResult.Failed)
    }

    @Test
    fun theStatusReceiverPublishesTheResult() {
        val results = mutableListOf<InstallResult>()
        val listener: (InstallResult) -> Unit = { results += it }
        UpdateInstaller.listeners += listener
        try {
            InstallStatusReceiver().onReceive(app, status(PackageInstaller.STATUS_FAILURE_ABORTED))
            assertEquals(listOf(InstallResult.Cancelled), results)
        } finally {
            UpdateInstaller.listeners -= listener
        }
    }

    @Test
    fun theStatusReceiverOpensAndroidsConfirmScreen() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        InstallStatusReceiver().onReceive(app, status(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm = confirm))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
