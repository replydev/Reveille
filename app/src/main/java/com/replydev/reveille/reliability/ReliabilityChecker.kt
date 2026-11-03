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
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.replydev.reveille.R
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.data.SettingsStore
import kotlinx.coroutines.flow.first

data class ReliabilityCheck(
    val id: String,
    @StringRes val title: Int,
    @StringRes val detail: Int,
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
                    "notifications", R.string.health_check_notifications_title,
                    R.string.health_check_notifications_detail,
                    NotificationManagerCompat.from(context).areNotificationsEnabled(), false,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    ReliabilityCheck(
                        "full_screen", R.string.health_check_full_screen_title,
                        R.string.health_check_full_screen_detail,
                        notifications.canUseFullScreenIntent(), false,
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
                    ),
                )
            }
            add(
                ReliabilityCheck(
                    "alarm_channel", R.string.health_check_alarm_channel_title,
                    R.string.health_check_alarm_channel_detail,
                    (alarmChannel?.importance ?: NotificationManager.IMPORTANCE_NONE) >= NotificationManager.IMPORTANCE_HIGH, false,
                    channelSettings(Notifier.CHANNEL_ALARM),
                ),
            )
            add(
                ReliabilityCheck(
                    "fallback_channel", R.string.health_check_fallback_channel_title,
                    R.string.health_check_fallback_channel_detail,
                    fallbackChannel != null &&
                        fallbackChannel.importance >= NotificationManager.IMPORTANCE_HIGH &&
                        fallbackChannel.sound != null,
                    false,
                    channelSettings(Notifier.CHANNEL_ALARM_FALLBACK),
                ),
            )
            add(
                ReliabilityCheck(
                    "exact_alarms", R.string.health_check_exact_alarms_title,
                    R.string.health_check_exact_alarms_detail,
                    alarms.canScheduleExactAlarms(), false,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                ),
            )
            add(
                ReliabilityCheck(
                    "battery_optimization", R.string.health_check_battery_optimization_title,
                    R.string.health_check_battery_optimization_detail,
                    power.isIgnoringBatteryOptimizations(packageName), false,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                ),
            )
            add(
                ReliabilityCheck(
                    "alarm_volume", R.string.health_check_alarm_volume_title,
                    R.string.health_check_alarm_volume_detail,
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
        fun manual(id: String, @StringRes title: Int, @StringRes detail: Int, fix: Intent?) =
            ReliabilityCheck(id, title, detail, id in confirmed, true, fix)
        if (!isXiaomi()) {
            return listOf(
                manual(
                    "oem_battery", R.string.health_check_oem_battery_title,
                    R.string.health_check_oem_battery_detail,
                    appDetailsIntent(context),
                ),
            )
        }
        return listOf(
            manual(
                "miui_autostart", R.string.health_check_miui_autostart_title,
                R.string.health_check_miui_autostart_detail,
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            ),
            manual(
                "miui_battery", R.string.health_check_miui_battery_title,
                R.string.health_check_miui_battery_detail,
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", packageName)
                    .putExtra("package_label", "Reveille"),
            ),
            manual(
                "miui_permissions", R.string.health_check_miui_permissions_title,
                R.string.health_check_miui_permissions_detail,
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName),
            ),
            manual(
                "miui_recents", R.string.health_check_miui_recents_title,
                R.string.health_check_miui_recents_detail,
                null,
            ),
        )
    }

    private fun isXiaomi(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")
}

fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
