package com.replydev.reveille.reliability

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File

@Config(sdk = [31, 33, 35])
@RunWith(AndroidJUnit4::class)
class ReliabilityCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var checker: ReliabilityChecker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Notifier(context).createChannels()
        settings = SettingsStore(PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") })
        checker = ReliabilityChecker(context, settings)
    }

    @After
    fun tearDown() {
        // Build fields are static: never let one test's manufacturer leak into the next.
        ShadowBuild.reset()
    }

    @Test
    fun otherPhonesGetOneGenericManualCheck() = runBlocking {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")
        assertEquals(listOf("oem_battery"), checker.checks().filter { it.manual }.map { it.id })
    }

    @Test
    fun xiaomiPhonesGetHyperOsChecks() = runBlocking {
        ShadowBuild.setManufacturer("Xiaomi")
        ShadowBuild.setBrand("Redmi")
        assertEquals(
            listOf("miui_autostart", "miui_battery", "miui_permissions", "miui_recents"),
            checker.checks().filter { it.manual }.map { it.id },
        )
    }

    @Test
    fun pocoBrandCountsAsXiaomi() = runBlocking {
        ShadowBuild.setManufacturer("SomeOdm")
        ShadowBuild.setBrand("POCO")
        assertTrue(checker.checks().any { it.id == "miui_autostart" })
    }

    @Test
    fun confirmingAManualCheckMarksItOk() = runBlocking {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")
        assertFalse(checker.checks().single { it.id == "oem_battery" }.ok)
        settings.setConfirmed("oem_battery", true)
        assertTrue(checker.checks().single { it.id == "oem_battery" }.ok)
    }

    @Test
    fun alarmAndFallbackChannelsAreOkOnceCreated() = runBlocking {
        val checks = checker.checks().associateBy { it.id }
        assertTrue(checks.getValue("alarm_channel").ok)
        assertTrue(checks.getValue("fallback_channel").ok)
    }

    @Test
    fun missingFallbackChannelIsReported() = runBlocking {
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(Notifier.CHANNEL_ALARM_FALLBACK)
        val fallback = checker.checks().single { it.id == "fallback_channel" }
        assertFalse(fallback.ok)
        assertFalse(fallback.manual)
        assertFalse(checker.allGood())
    }

    @Test
    fun fullScreenCheckOnlyFromAndroid14() = runBlocking {
        val present = checker.checks().any { it.id == "full_screen" }
        assertEquals(Build.VERSION.SDK_INT >= 34, present)
    }
}
