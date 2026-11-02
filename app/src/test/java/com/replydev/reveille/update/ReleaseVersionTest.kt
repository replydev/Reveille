package com.replydev.reveille.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun parsesTagsAndUsesTheBuildVersionCodeFormula() {
        val v = ReleaseVersion.parseTag("v0.3.0")!!
        assertEquals(ReleaseVersion(0, 3, 0), v)
        assertEquals(3_000, v.versionCode)
        assertEquals("0.3.0", v.name)
        // Same formula as release.yml: major * 1,000,000 + minor * 1,000 + patch.
        assertEquals(1_002_003, ReleaseVersion.parseTag("v1.2.3")!!.versionCode)
        assertEquals(12_034_056, ReleaseVersion.parseTag("v12.34.56")!!.versionCode)
    }

    @Test
    fun rejectsTagsOutsideTheReleaseFormat() {
        listOf("0.3.0", "v0.3", "v0.3.0-rc1", "v0.3.0 ", "V0.3.0", "v0.03.x", "", "latest").forEach {
            assertNull(it, ReleaseVersion.parseTag(it))
        }
    }

    @Test
    fun rejectsComponentsThatWouldCollideOrOverflowTheVersionCode() {
        assertNull(ReleaseVersion.parseTag("v0.1000.0")) // would equal v1.0.0
        assertNull(ReleaseVersion.parseTag("v0.0.1000"))
        assertNull(ReleaseVersion.parseTag("v2147.999.999")) // > Int.MAX_VALUE
        assertNull(ReleaseVersion.parseTag("v99999999999.0.0"))
        assertEquals(2_146_999_999, ReleaseVersion.parseTag("v2146.999.999")?.versionCode)
    }
}
