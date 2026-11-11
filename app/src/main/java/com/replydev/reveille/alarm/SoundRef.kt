package com.replydev.reveille.alarm

import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.engine.Source

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

/**
 * The sound for [armed]: the override of a one-off among its sources (one-offs win in a merged occurrence: they were
 * set for that alarm), else its policy's override, else the default. A snooze carries its alarm's sources.
 */
fun resolveSound(armed: Armed, settings: RingSettings): SoundRef {
    val oneOff = (listOf(armed.key) + armed.sourceKeys).firstNotNullOfOrNull { key ->
        (Source.parse(key) as? Source.OneOffAlarm)?.let { settings.oneOffSounds[it.oneOffId] }
    }
    return oneOff ?: ringPolicyId(armed)?.let(settings.policySounds::get) ?: settings.sound
}

/** Something MediaPlayer can be pointed at. */
sealed interface SoundSource {
    data class File(val path: String) : SoundSource
    data class Uri(val uri: String) : SoundSource
    data object SystemDefault : SoundSource
    /** R.raw.fallback_alarm: always readable, even before the first unlock. */
    data object Bundled : SoundSource
}

/** What to try, in order: the chosen sound, the system default alarm, then the bundled sound. */
fun soundCandidates(ref: SoundRef, importedPath: (String) -> String): List<SoundSource> = buildList {
    when (ref) {
        is SoundRef.Imported -> add(SoundSource.File(importedPath(ref.id)))
        is SoundRef.SystemTone -> add(SoundSource.Uri(ref.uri))
        SoundRef.SystemDefault -> Unit
    }
    add(SoundSource.SystemDefault)
    add(SoundSource.Bundled)
}

/** Runs [attempt] on each candidate until one succeeds and returns it; if none does, throws the last failure. */
fun prepareFirst(candidates: List<SoundSource>, attempt: (SoundSource) -> Unit, onFailure: (SoundSource, Exception) -> Unit): SoundSource {
    var last: Exception? = null
    for (source in candidates) {
        try {
            attempt(source)
            return source
        } catch (e: Exception) {
            onFailure(source, e)
            last = e
        }
    }
    throw last ?: IllegalStateException("No sound candidates")
}
