package com.replydev.reveille.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/** What identifies an APK: its package, version and the SHA-256 of each signing certificate. */
data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

interface ApkInspector {
    fun installed(): ApkIdentity

    /** Null if the file can't be parsed as a signed APK. */
    fun archive(file: File): ApkIdentity?
}

sealed interface VerifyResult {
    data object Ok : VerifyResult

    data class Failed(val reason: FailReason) : VerifyResult
}

/**
 * The checks after the checksum (spec §1.3): same package, same signing certificates as the installed app, and the
 * newer versionCode the release's tag promised. Fails closed: anything unreadable is a signature failure.
 */
class ApkVerifier(private val inspector: ApkInspector) {
    fun verify(file: File, expectedVersionCode: Int): VerifyResult {
        val installed = inspector.installed()
        val archive = inspector.archive(file) ?: return VerifyResult.Failed(FailReason.SIGNATURE)
        if (archive.packageName != installed.packageName) return VerifyResult.Failed(FailReason.PACKAGE)
        if (archive.signers.isEmpty() || archive.signers != installed.signers) return VerifyResult.Failed(FailReason.SIGNATURE)
        if (archive.versionCode <= installed.versionCode || archive.versionCode != expectedVersionCode.toLong()) {
            return VerifyResult.Failed(FailReason.PACKAGE)
        }
        return VerifyResult.Ok
    }
}

/** Reads identities with `GET_SIGNING_CERTIFICATES` (API 28+), comparing `signingInfo.apkContentsSigners`. */
class AndroidApkInspector(private val context: Context) : ApkInspector {
    private val packageManager get() = context.packageManager

    override fun installed(): ApkIdentity =
        identity(packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
            ?: error("The installed package has no signing info")

    override fun archive(file: File): ApkIdentity? = try {
        packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let(::identity)
    } catch (_: Exception) {
        null
    }

    private fun identity(info: PackageInfo): ApkIdentity? {
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        return ApkIdentity(info.packageName, info.longVersionCode, signers.map(::fingerprint).toSet())
    }

    private fun fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
