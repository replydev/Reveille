package com.replydev.reveille.update

import com.replydev.reveille.update.ReleaseNotes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    /** The body `release.yml` publishes: `scripts/semver.sh notes` plus the Install section. */
    private val v030 = """
        ## Features

        - feat(ui): replace the launcher icon with the Daybreak mark (#7)

        ## Fixes

        - fix(ui): use the shared axis transition for system Back instead of the default scale-out (#6)

        ## Install

        Download `reveille-v0.3.0.apk` and open it on an Android 12+ phone (allow installs from this source).
        Verify it with `sha256sum -c reveille-v0.3.0.apk.sha256`.
    """.trimIndent()

    @Test
    fun keepsTheSemverSectionsAndDropsInstall() {
        val notes = ReleaseNotes.parse(v030)
        assertEquals(listOf(Kind.FEATURES, Kind.FIXES), notes.sections.map { it.kind })
        assertEquals(listOf("Replace the launcher icon with the Daybreak mark"), notes.sections[0].bullets)
        assertEquals(
            listOf("Use the shared axis transition for system Back instead of the default scale-out"),
            notes.sections[1].bullets,
        )
        assertTrue(notes.fallback.isEmpty())
        assertEquals("Replace the launcher icon with the Daybreak mark", notes.headline)
    }

    @Test
    fun readsAllFourSectionsInOrderWithCrLf() {
        val body = "## Breaking changes\r\n\r\n- feat(alarm)!: drop the old schema\r\n\r\n## Features\r\n\r\n" +
            "- feat: add widgets\r\n\r\n## Fixes\r\n\r\n- fix(data): keep rule ids\r\n\r\n" +
            "## Performance\r\n\r\n- perf(engine): cache the timeline\r\n"
        val notes = ReleaseNotes.parse(body)
        assertEquals(listOf(Kind.BREAKING, Kind.FEATURES, Kind.FIXES, Kind.PERFORMANCE), notes.sections.map { it.kind })
        assertEquals(listOf("Drop the old schema"), notes.sections[0].bullets)
        assertEquals(listOf("Cache the timeline"), notes.sections[3].bullets)
        // The headline prefers a feature over a breaking change.
        assertEquals("Add widgets", notes.headline)
    }

    @Test
    fun stripsLinksBackticksAndPullRequestReferences() {
        val notes = ReleaseNotes.parse(
            "## Fixes\n\n- fix(ui): honour `Faint` in [the spec](https://example.com/spec) (#12)\n",
        )
        assertEquals(listOf("Honour Faint in the spec"), notes.sections.single().bullets)
    }

    @Test
    fun truncatesToSixBulletsAndCountsTheRest() {
        val body = "## Features\n\n" + (1..9).joinToString("\n") { "- feat: item $it" }
        val section = ReleaseNotes.parse(body).sections.single()
        assertEquals((1..6).map { "Item $it" }, section.bullets)
        assertEquals(3, section.more)
    }

    @Test
    fun headlineFallsBackToFixesThenAnything() {
        assertEquals("Keep rule ids", ReleaseNotes.parse("## Fixes\n\n- fix: keep rule ids\n").headline)
        assertEquals("Faster", ReleaseNotes.parse("## Performance\n\n- perf: faster\n").headline)
    }

    @Test
    fun withoutSectionsShowsTheFirstThreeNonEmptyLines() {
        val notes = ReleaseNotes.parse("\n# Hello\n\nFirst `line`\n\n- second\nthird\nfourth\n")
        assertTrue(notes.sections.isEmpty())
        assertEquals(listOf("Hello", "First line", "Second"), notes.fallback)
        assertEquals("Hello", notes.headline)
    }

    @Test
    fun anInstallOnlyBodyFallsBackToNothingUseful() {
        val notes = ReleaseNotes.parse("## Install\n\nDownload it.\n")
        assertTrue(notes.sections.isEmpty())
        assertTrue(notes.fallback.isEmpty())
        assertNull(notes.headline)
    }

    @Test
    fun emptyBody() {
        val notes = ReleaseNotes.parse("")
        assertTrue(notes.sections.isEmpty())
        assertNull(notes.headline)
    }

    @Test
    fun sectionsAreFoundByTheirEnglishHeadingsWhateverTheAppLanguage() {
        // GitHub release bodies are English; the shown titles are resources.
        assertEquals(listOf("Breaking changes", "Features", "Fixes", "Performance"), Kind.entries.map { it.heading })
        assertEquals(Kind.entries.size, Kind.entries.map { it.title }.toSet().size)
    }
}
