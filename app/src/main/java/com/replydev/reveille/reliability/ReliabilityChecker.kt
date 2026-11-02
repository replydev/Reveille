package com.replydev.reveille.reliability

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.data.SettingsStore
import kotlinx.coroutines.flow.first

data class ReliabilityCheck(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val manual: Boolean,
    val fix: Intent?,
)

/**
 * Automatic checks read system state. Manual checks cover OEM settings apps cannot read; the user confirms them by
 * hand. Xiaomi, Redmi and POCO phones get four HyperOS checks, other phones one generic manufacturer-battery check.
 */
class ReliabilityChecker(private val context: Context, private val settings: SettingsStore) {
    private val packageName = context.packageName
    private val packageUri = Uri.fromParts("package", packageName, null)

    suspend fun checks(): List<ReliabilityCheck> = automaticChecks() + manualChecks(settings.confirmedChecks.first())

    suspend fun allGood(): Boolean = checks().all { it.ok }

    private fun automaticChecks(): List<ReliabilityCheck> {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val alarmChannel = notifications.getNotificationChannel(Notifier.CHANNEL_ALARM)
        val fallbackChannel = notifications.getNotificationChannel(Notifier.CHANNEL_ALARM_FALLBACK)
        return buildList {
            add(
                ReliabilityCheck(
                    "notifications", "Notifications allowed",
                    "The ringing alarm and missed-alarm warnings are notifications.",
                    NotificationManagerCompat.from(context).areNotificationsEnabled(), false,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    ReliabilityCheck(
                        "full_screen", "Full-screen alarms allowed",
                        "Lets the alarm screen appear over the lock screen.",
                        notifications.canUseFullScreenIntent(), false,
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
                    ),
                )
            }
            add(
                ReliabilityCheck(
                    "alarm_channel", "Alarm notifications at high importance",
                    "If lowered, the alarm screen will not pop up.",
                    (alarmChannel?.importance ?: NotificationManager.IMPORTANCE_NONE) >= NotificationManager.IMPORTANCE_HIGH, false,
                    channelSettings(Notifier.CHANNEL_ALARM),
                ),
            )
            add(
                ReliabilityCheck(
                    "fallback_channel", "Fallback alarms at high importance with sound",
                    "The fallback alarm is the last resort when the normal alarm cannot start. It rings through this " +
                        "notification channel, so it must stay at high importance and keep its sound.",
                    fallbackChannel != null &&
                        fallbackChannel.importance >= NotificationManager.IMPORTANCE_HIGH &&
                        fallbackChannel.sound != null,
                    false,
                    channelSettings(Notifier.CHANNEL_ALARM_FALLBACK),
                ),
            )
            add(
                ReliabilityCheck(
                    "exact_alarms", "Exact alarms allowed",
                    "Required to ring at the exact minute.",
                    alarms.canScheduleExactAlarms(), false,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                ),
            )
            add(
                ReliabilityCheck(
                    "battery_optimization", "Battery optimization off",
                    "Battery-optimized apps can be delayed or stopped in the background.",
                    power.isIgnoringBatteryOptimizations(packageName), false,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                ),
            )
            add(
                ReliabilityCheck(
                    "alarm_volume", "Alarm volume not muted",
                    "Alarms play on the alarm volume, which is separate from the ringer.",
                    audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0, false,
                    Intent(Settings.ACTION_SOUND_SETTINGS),
                ),
            )
        }
    }

    private fun channelSettings(channelId: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

    private fun manualChecks(confirmed: Set<String>): List<ReliabilityCheck> {
        fun manual(id: String, title: String, detail: String, fix: Intent?) = ReliabilityCheck(id, title, detail, id in confirmed, true, fix)
        if (!isXiaomi()) {
            return listOf(
                manual(
                    "oem_battery", "Manufacturer battery settings",
                    "Some phones stop apps despite Android's own settings. Open the app's battery settings, choose Unrestricted, then tick Done.",
                    appDetailsIntent(context),
                ),
            )
        }
        return listOf(
            manual(
                "miui_autostart", "Autostart on",
                "Without Autostart, HyperOS does not tell Reveille that the phone restarted, so alarms are lost after a reboot.",
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            ),
            manual(
                "miui_battery", "Battery saver: No restrictions",
                "In the app's battery saver setting, choose \"No restrictions\".",
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", packageName)
                    .putExtra("package_label", "Reveille"),
            ),
            manual(
                "miui_permissions", "Lock screen and pop-up permissions",
                "Under Other permissions, allow \"Show on Lock screen\" and \"Display pop-up windows while running in the background\".",
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName),
            ),
            manual(
                "miui_recents", "Locked in Recents",
                "Open the recent apps view, long-press Reveille and tap the lock icon.",
                null,
            ),
        )
    }

    private fun isXiaomi(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")
}

fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
