package com.replydev.reveille.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replydev.reveille.R
import com.replydev.reveille.engine.DayLabel
import com.replydev.reveille.engine.Relative
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class TranslationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test @Config(qualifiers = "it")
    fun italian() {
        assertEquals("Oggi", context.getString(R.string.nav_today))
        assertEquals("Sveglia", context.getString(R.string.oneoff_default_label))
        assertEquals("tra 1\u00A0min", Labels.of(context).relative(Relative.Minutes(1)))
        assertEquals("Domani", Labels.of(context).day(DayLabel.Tomorrow))
    }

    @Test @Config(qualifiers = "de")
    fun german() = assertEquals("Heute", context.getString(R.string.nav_today))

    @Test @Config(qualifiers = "es")
    fun spanish() = assertEquals("Hoy", context.getString(R.string.nav_today))

    @Test @Config(qualifiers = "fr")
    fun french() = assertEquals("Aujourd’hui", context.getString(R.string.nav_today))
}
