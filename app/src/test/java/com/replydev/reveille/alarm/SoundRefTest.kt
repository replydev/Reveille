package com.replydev.reveille.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundRefTest {
    @Test
    fun roundTrips() {
        listOf(
            SoundRef.SystemDefault,
            SoundRef.SystemTone("content://media/internal/audio/media/12?title=a=b"),
            SoundRef.Imported("0b6f2c3e-8d1a-4a43-9c55-2f4d1d7e9a10"),
        ).forEach { assertEquals(it, SoundRef.decode(it.encode())) }
    }

    @Test
    fun encodings() {
        assertEquals("default", SoundRef.SystemDefault.encode())
        assertEquals("tone:content://x", SoundRef.SystemTone("content://x").encode())
        assertEquals("file:abc", SoundRef.Imported("abc").encode())
    }

    @Test
    fun anythingUnreadableIsTheSystemDefault() {
        listOf(null, "", "garbage", "tone:", "file:", "file:../escape", "file:a/b").forEach {
            assertEquals(it, SoundRef.SystemDefault, SoundRef.decode(it))
        }
    }
}
