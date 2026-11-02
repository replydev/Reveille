package com.replydev.reveille.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The comparison behind [ApkVerifier]. Robolectric's package manager cannot parse a real signed APK, so the Android
 * side (reading [ApkIdentity] with `getPackageArchiveInfo`) sits behind [ApkInspector] and this tests every decision.
 */
class ApkVerifierTest {
    private val ours = "com.replydev.reveille"
    private val releaseKey = setOf("a1b2")
    private val installed = ApkIdentity(ours, 2_000, releaseKey)
    private val file = File("reveille-v0.3.0.apk")

    private fun verifier(archive: ApkIdentity?) = ApkVerifier(object : ApkInspector {
        override fun installed() = installed
        override fun archive(file: File) = archive
    })

    @Test
    fun sameKeySamePackageHigherVersionIsOk() {
        assertEquals(VerifyResult.Ok, verifier(ApkIdentity(ours, 3_000, releaseKey)).verify(file, expectedVersionCode = 3_000))
    }

    @Test
    fun anotherKeyIsRejected() {
        assertEquals(VerifyResult.Failed(FailReason.SIGNATURE), verifier(ApkIdentity(ours, 3_000, setOf("ffff"))).verify(file, 3_000))
        // An extra signer is not the same signer set either.
        assertEquals(VerifyResult.Failed(FailReason.SIGNATURE), verifier(ApkIdentity(ours, 3_000, releaseKey + "ffff")).verify(file, 3_000))
        assertEquals(VerifyResult.Failed(FailReason.SIGNATURE), verifier(ApkIdentity(ours, 3_000, emptySet())).verify(file, 3_000))
    }

    @Test
    fun anUnreadableArchiveIsRejected() {
        assertEquals(VerifyResult.Failed(FailReason.SIGNATURE), verifier(null).verify(file, 3_000))
    }

    @Test
    fun anotherPackageIsRejected() {
        assertEquals(VerifyResult.Failed(FailReason.PACKAGE), verifier(ApkIdentity("com.evil", 3_000, releaseKey)).verify(file, 3_000))
    }

    @Test
    fun theSameOrAnOlderVersionIsRejected() {
        assertEquals(VerifyResult.Failed(FailReason.PACKAGE), verifier(ApkIdentity(ours, 2_000, releaseKey)).verify(file, 2_000))
        assertEquals(VerifyResult.Failed(FailReason.PACKAGE), verifier(ApkIdentity(ours, 1_000, releaseKey)).verify(file, 1_000))
    }

    @Test
    fun theVersionMustBeTheOneTheReleasePromised() {
        assertEquals(VerifyResult.Failed(FailReason.PACKAGE), verifier(ApkIdentity(ours, 2_500, releaseKey)).verify(file, 3_000))
    }
}
