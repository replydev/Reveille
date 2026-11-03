package com.replydev.reveille.update

import android.content.res.Resources
import com.replydev.reveille.R
import com.replydev.reveille.text.Labels
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The update screens' strings that depend on data. */
class UpdateText(private val res: Resources, private val labels: Labels) {
    /** Settings › Last checked: "Today, 09:12", "Yesterday, 23:50", "30 Oct, 07:05" or "Never". */
    fun lastChecked(at: Instant?, now: Instant, zone: ZoneId): String {
        if (at == null) return res.getString(R.string.update_never)
        val local = at.atZone(zone)
        val days = Duration.between(local.toLocalDate().atStartOfDay(zone), now.atZone(zone).toLocalDate().atStartOfDay(zone)).toDays()
        val day = when (days) {
            0L -> res.getString(R.string.time_today)
            1L -> res.getString(R.string.time_yesterday)
            else -> labels.formats.dayMonth(local.toLocalDate())
        }
        return res.getString(R.string.update_last_checked_at, day, labels.formats.time(local.toLocalTime()))
    }

    fun released(at: Instant, zone: ZoneId): String = labels.formats.dayMonth(at.atZone(zone).toLocalDate())

    /** "6.4 MB", in decimal megabytes. */
    fun size(bytes: Long): String = res.getString(R.string.update_size_mb, labels.formats.megabytes(bytes))

    /** Non-breaking spaces keep "2 Nov" and "6.4 MB" whole when the line wraps. */
    fun sheetSubtitle(release: Release, installedVersionName: String, zone: ZoneId): String {
        val date = released(release.publishedAt, zone).replace(' ', '\u00A0')
        val size = size(release.apkSize).replace(' ', '\u00A0')
        return res.getString(R.string.update_sheet_subtitle, installedVersionName, date, size)
    }

    /** "Work rings in 18 min", rounded up so it never claims 0 minutes while the alarm is still ahead. */
    fun ringsIn(label: String, at: Instant, now: Instant): String {
        val seconds = Duration.between(now, at).seconds
        if (seconds <= 0) return res.getString(R.string.update_due_now, label)
        val minutes = ((seconds + 59) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return res.getQuantityString(R.plurals.update_rings_in, minutes, label, minutes)
    }

    fun time(at: Instant, zone: ZoneId): String = labels.formats.time(at.atZone(zone).toLocalTime())
}

/** "Remind me" fires this long after the alarm, once it has rung. */
fun reminderAt(alarmAt: Instant): Instant = alarmAt.plus(Duration.ofMinutes(5))
