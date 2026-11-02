package com.replydev.reveille.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Sha256FileTest {
    private val hex = "6bf1e5091502731759aaed8a9714325a28d05f9a160b1eeadf296064ce913a7e"

    @Test
    fun parsesSha256sumOutput() {
        assertEquals(hex, Sha256File.parse("$hex  reveille-v0.2.0.apk\n"))
        assertEquals(hex, Sha256File.parse(hex))
        assertEquals(hex, Sha256File.parse("  $hex *reveille-v0.2.0.apk\r\n"))
    }

    @Test
    fun normalisesUppercaseHex() {
        assertEquals(hex, Sha256File.parse("${hex.uppercase()}  reveille-v0.2.0.apk"))
    }

    @Test
    fun rejectsShortLongAndGarbageInput() {
        assertNull(Sha256File.parse(hex.dropLast(1) + "  reveille-v0.2.0.apk"))
        assertNull(Sha256File.parse(hex + "0  reveille-v0.2.0.apk"))
        assertNull(Sha256File.parse(hex.dropLast(1) + "g  reveille-v0.2.0.apk"))
        assertNull(Sha256File.parse(""))
        assertNull(Sha256File.parse("<html>Not Found</html>"))
        assertNull(Sha256File.parse("reveille-v0.2.0.apk  $hex"))
    }
}
