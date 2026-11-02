package com.replydev.reveille.update

import com.replydev.reveille.engine.TimeText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The update screens' strings that depend on data. */
object UpdateText {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Settings › Last checked: "Today, 09:12", "Yesterday, 23:50", "30 Oct, 07:05" or "Never". */
    fun lastChecked(at: Instant?, now: Instant, zone: ZoneId): String {
        if (at == null) return "Never"
        val local = at.atZone(zone)
        val days = Duration.between(local.toLocalDate().atStartOfDay(zone), now.atZone(zone).toLocalDate().atStartOfDay(zone)).toDays()
        val day = when (days) {
            0L -> "Today"
            1L -> "Yesterday"
            else -> local.format(dayMonth)
        }
        return "$day, ${TimeText.time(local.toLocalTime())}"
    }

    fun released(at: Instant, zone: ZoneId): String = at.atZone(zone).format(dayMonth)

    /** Decimal megabytes with one decimal, as Android's own storage screens count them. */
    fun size(bytes: Long): String = String.format(Locale.ENGLISH, "%.1f MB", bytes / 1_000_000.0)

    /** Non-breaking spaces keep "2 Nov" and "6.4 MB" whole when the line wraps. */
    fun sheetSubtitle(release: Release, installedVersionName: String, zone: ZoneId): String {
        val date = released(release.publishedAt, zone).replace(' ', '\u00A0')
        val size = size(release.apkSize).replace(' ', '\u00A0')
        return "From v$installedVersionName · released $date · $size"
    }

    /** "Work rings in 18 min", rounded up so it never claims 0 minutes while the alarm is still ahead. */
    fun ringsIn(label: String, at: Instant, now: Instant): String {
        val seconds = Duration.between(now, at).seconds
        if (seconds <= 0) return "$label is due now"
        return "$label rings in ${(seconds + 59) / 60} min"
    }

    /** "Remind me" fires this long after the alarm, once it has rung. */
    fun remindAfter(alarmAt: Instant): Instant = alarmAt.plus(Duration.ofMinutes(5))

    fun time(at: Instant, zone: ZoneId): String = TimeText.time(at.atZone(zone).toLocalTime())
}
