package com.replydev.reveille.update

import androidx.annotation.StringRes
import com.replydev.reveille.R

/**
 * Release notes as the sheet shows them. The body comes from `scripts/semver.sh notes` followed by `## Install`:
 * the four semver sections are kept, everything else is dropped, and each bullet loses its commit prefix
 * (`feat(ui): `), its pull request reference (`(#7)`), Markdown links and backticks.
 */
data class ReleaseNotes(
    val sections: List<Section>,
    /** The first lines of a body without sections; empty when there are sections. */
    val fallback: List<String>,
) {
    /** [heading] is the section's Markdown heading in the (English) release body; [title] is what the sheet shows. */
    enum class Kind(val heading: String, @StringRes val title: Int, val accent: Boolean) {
        BREAKING("Breaking changes", R.string.update_notes_breaking, accent = true),
        FEATURES("Features", R.string.update_notes_features, accent = true),
        FIXES("Fixes", R.string.update_notes_fixes, accent = false),
        PERFORMANCE("Performance", R.string.update_notes_performance, accent = false),
    }

    /** [bullets] holds at most [MAX_BULLETS]; [more] counts the rest ("and N more"). */
    data class Section(val kind: Kind, val bullets: List<String>, val more: Int)

    /** One line for the Today card and the notification: the first feature, else the first fix, else anything. */
    val headline: String?
        get() = sections.firstOrNull { it.kind == Kind.FEATURES }?.bullets?.firstOrNull()
            ?: sections.firstOrNull { it.kind == Kind.FIXES }?.bullets?.firstOrNull()
            ?: sections.firstNotNullOfOrNull { it.bullets.firstOrNull() }
            ?: fallback.firstOrNull()

    companion object {
        const val MAX_BULLETS = 6
        private const val MAX_FALLBACK_LINES = 3

        private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
        private val BULLET = Regex("""^[-*]\s+(.*)$""")
        private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val COMMIT_PREFIX = Regex("""^[a-z]+(\([^)]*\))?!?:\s+""")
        private val PR_REFERENCE = Regex("""\s*\(#\d+\)$""")

        fun parse(body: String): ReleaseNotes {
            val bullets = linkedMapOf<Kind, MutableList<String>>()
            val loose = mutableListOf<String>()
            var current: Kind? = null
            var inInstall = false
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val heading = HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()
                if (heading != null && line.startsWith("## ")) {
                    current = Kind.entries.firstOrNull { it.heading.equals(heading, ignoreCase = true) }
                    inInstall = heading.equals("Install", ignoreCase = true)
                    if (current != null) bullets.getOrPut(current) { mutableListOf() }
                    continue
                }
                if (inInstall) continue
                val bullet = BULLET.matchEntire(line)?.groupValues?.get(1)
                if (current != null) {
                    if (bullet != null) clean(bullet).takeIf { it.isNotEmpty() }?.let { bullets.getValue(current) += it }
                } else {
                    clean(bullet ?: heading ?: line).takeIf { it.isNotEmpty() }?.let { loose += it }
                }
            }
            val sections = bullets.filterValues { it.isNotEmpty() }.map { (kind, items) ->
                Section(kind, items.take(MAX_BULLETS), (items.size - MAX_BULLETS).coerceAtLeast(0))
            }
            return ReleaseNotes(sections, if (sections.isEmpty()) loose.take(MAX_FALLBACK_LINES) else emptyList())
        }

        private fun clean(text: String): String {
            val plain = text.replace(LINK, "$1").replace("`", "")
                .replace(COMMIT_PREFIX, "")
                .replace(PR_REFERENCE, "")
                .trim()
            return plain.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
