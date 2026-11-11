package com.replydev.reveille.alarm

/**
 * Which sound an alarm plays (docs/superpowers/specs/2026-11-11-custom-alarm-sounds-design.md). Stored with [encode];
 * [decode] never throws, and anything unreadable is [SystemDefault].
 */
sealed interface SoundRef {
    fun encode(): String

    /** The phone's default alarm sound (RingtoneManager.getDefaultUri(TYPE_ALARM)). */
    data object SystemDefault : SoundRef {
        override fun encode() = "default"
    }

    /** A tone from the system picker. Not readable before the first unlock; the fallback chain covers that. */
    data class SystemTone(val uri: String) : SoundRef {
        override fun encode() = "tone:$uri"
    }

    /** A file the user imported into device-protected storage (data/SoundLibrary). */
    data class Imported(val id: String) : SoundRef {
        override fun encode() = "file:$id"
    }

    companion object {
        fun decode(value: String?): SoundRef = when {
            value == null -> SystemDefault
            value.startsWith("tone:") && value.length > 5 -> SystemTone(value.substring(5))
            // An id is a file name in sounds/: never a path.
            value.startsWith("file:") && value.length > 5 && value.substring(5).let { '/' !in it && it != "." && it != ".." && !it.startsWith("..") } ->
                Imported(value.substring(5))
            else -> SystemDefault
        }
    }
}
