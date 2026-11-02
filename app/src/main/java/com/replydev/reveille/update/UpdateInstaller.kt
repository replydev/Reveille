package com.replydev.reveille.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

sealed interface InstallResult {
    data object Success : InstallResult

    /** Android wants the user to confirm; [confirm] is its dialog. */
    data class NeedsUserAction(val confirm: Intent) : InstallResult

    /** The user said no in Android's dialog. */
    data object Cancelled : InstallResult

    data class Failed(val message: String) : InstallResult
}

/** Hands a verified APK to Android through a [PackageInstaller] session (not the deprecated `ACTION_VIEW` route). */
class UpdateInstaller(private val context: Context) {
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings › Install unknown apps › Reveille. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** Writes [apk] into a new session and commits it. The outcome arrives at [InstallStatusReceiver]. Returns the id. */
    fun install(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("reveille.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val status = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS),
                    // Mutable: the installer fills in the status extras.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    companion object {
        const val ACTION_STATUS = "com.replydev.reveille.update.INSTALL_STATUS"

        /** The sheet listens while it is open. Results that arrive with nobody listening are only logged. */
        val listeners: MutableSet<(InstallResult) -> Unit> = CopyOnWriteArraySet()

        fun result(intent: Intent): InstallResult =
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?.let { InstallResult.NeedsUserAction(it) }
                        ?: InstallResult.Failed("Android couldn't install the update.")
                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
                PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.Failed("Not enough storage to install the update.")
                PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.Failed("The update conflicts with the installed app.")
                PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallResult.Failed("Android blocked the install.")
                PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    InstallResult.Failed("The update isn't valid for this phone.")
                else -> InstallResult.Failed("Android couldn't install the update.")
            }
    }
}

/** Receives the session's status: opens Android's confirm dialog when asked, and tells the sheet the outcome. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        val result = UpdateInstaller.result(intent)
        if (result is InstallResult.NeedsUserAction) {
            try {
                context.startActivity(result.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.e("Reveille", "Could not open the install confirmation", e)
            }
        }
        if (result is InstallResult.Failed) {
            Log.w("Reveille", "Update install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
        UpdateInstaller.listeners.forEach { it(result) }
    }
}
