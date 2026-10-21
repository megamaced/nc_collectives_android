package com.megamaced.nccollectives.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text the user reads belongs in string resources, where translators can
 * reach it. This scans the app's sources for the shapes hardcoded UI text
 * takes — a literal handed to `Text`; a label, title, message or content
 * description; a snackbar, a toast, an error, an icon button's name; a
 * `when` branch, `else`, `?:` fallback or `return` that yields words; a
 * sentence on a line of its own — and fails on any it finds that isn't
 * either:
 *
 * - in [BASELINE], the hardcoded text that predates the translation work,
 *   which may only shrink: an entry that no longer matches anything fails
 *   too, so moving a string into resources means deleting its entry; or
 * - marked on its line with `i18n-ignore` and why, for text that is not
 *   for translating (a product name, a separator, markdown syntax).
 *
 * It is a regression aid, not a proof: text assembled indirectly can pass
 * it, so review still looks for that. Set `I18N_UPDATE_BASELINE=1` to
 * rewrite [BASELINE] from the current sources.
 */
class HardcodedUiTextTest {
    @Test
    fun noNewHardcodedUiText() {
        val found = findings()
        if (System.getenv("I18N_UPDATE_BASELINE") == "1") {
            BASELINE.writeText(found.joinToString("") { "$it\n" })
            return
        }
        val baseline = BASELINE.readLines().filter { it.isNotBlank() }

        val unlisted = found.minusEach(baseline)
        val stale = baseline.minusEach(found)

        assertTrue(
            "Hardcoded UI text, which belongs in strings.xml (or mark the line i18n-ignore with why):\n" +
                unlisted.joinToString("\n"),
            unlisted.isEmpty(),
        )
        assertTrue(
            "Baseline entries that no longer match anything: delete them from $BASELINE:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    @Test
    fun eachShapeOfUiTextIsCaught() {
        listOf(
            """Text("Save")""",
            """text = "Nothing here yet",""",
            """seeAllLabel = "See all",""",
            """contentDescription = if (open) "Collapse" else "Expand",""",
            """val who = alert.actorName ?: "Someone"""",
            """val page = alert.pageTitle ?: "a page"""",
            """"This alert will be dismissed on the server.",""",
            """Attention("attention", "Needs attention"),""",
            """ToolbarButton(Icons.Filled.FormatBold, "Bold") {""",
            """.makeText(context, "Copied", Toast.LENGTH_SHORT)""",
            """CustomAccessibilityAction("Dismiss") {""",
            """text = ui.collectiveName.ifBlank { "Pages" },""",
            """subtitle = name?.let { "By ${'$'}it" },""",
            """val base = "Pages cached on this device will be removed and ${'$'}{account.loginName}'s " +""",
            """return if (kind == null) role else "${'$'}kind · ${'$'}role"""",
            """return ApiResult.Unexpected(IllegalStateException("Page ${'$'}pageId not cached"))""",
        ).forEach { line -> assertTrue("Missed: $line", line.isUiText()) }
    }

    @Test
    fun codeThatIsNotUiTextPasses() {
        listOf(
            """items(pages, key = { "page-${'$'}{it.id}" }) { page ->""",
            """val base = if (url.endsWith('/')) url else "${'$'}url/"""",
            """"Sync HTTP %d on collective %d: %s",""",
            """"Server sent a weak ETag (%s); If-Match cannot guarantee it",""",
            """throw IOException("Unable to open ${'$'}uri") // i18n-ignore: shown as a network failure""",
            """.d("Edit route: plain (preference=PreferCollaborative, server=unavailable)")""",
            """"`pageId` INTEGER NOT NULL, " +""",
        ).forEach { line -> assertFalse("Caught: $line", line.isUiText()) }
    }

    /** Every match in the sources, as `path<TAB>trimmed line`, once per matching line. */
    private fun findings(): List<String> =
        SOURCES
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file -> NOT_UI.any { it in file.invariantSeparatorsPath } }
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(SOURCES).invariantSeparatorsPath
                file
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isUiText() }
                    .map { "$path\t$it" }
            }.toList()

    private fun String.isUiText(): Boolean = isCode() && PATTERNS.any { it.containsMatchIn(this) }

    private fun String.isCode(): Boolean =
        !startsWith("//") &&
            !startsWith("*") &&
            !startsWith("/*") &&
            "i18n-ignore" !in this &&
            !LOG_LEVEL.containsMatchIn(this) &&
            SKIP.none { it in this }

    /** This list less [other], one occurrence at a time. */
    private fun List<String>.minusEach(other: List<String>): List<String> {
        val remaining = other.groupingBy { it }.eachCount().toMutableMap()
        return filter { line ->
            val left = remaining[line] ?: 0
            if (left > 0) {
                remaining[line] = left - 1
                false
            } else {
                true
            }
        }
    }

    private companion object {
        val SOURCES = File("src/main/java")
        val BASELINE = File("src/test/resources/i18n/hardcoded-ui-text-baseline.txt")

        /** A string literal with at least one letter in it. */
        const val WORDS = "\"[^\"]*\\p{L}[^\"]*\""

        /** A string literal that reads as words: a capitalised word first, or two words before any template. */
        const val PHRASE = "\"(?:\\p{Lu}\\p{Ll}|[^\"$]*\\p{L} \\p{L})[^\"]*\""

        val PATTERNS =
            listOf(
                Regex("""\bText\(\s*(text\s*=\s*)?$WORDS"""),
                Regex("""\b(text|\w*([Ll]abel|[Tt]itle|[Mm]essage|[Dd]escription|[Pp]laceholder|Text|[Cc]aption))\s*=\s*$WORDS"""),
                Regex("""showSnackbar\(\s*(message\s*=\s*)?$WORDS"""),
                Regex("""UiText\.Raw\(\s*$WORDS"""),
                Regex("""\b[A-Z]\w*Error\(\s*$WORDS"""),
                Regex("""\bError\(\s*$WORDS"""),
                Regex("""const val [A-Z_]+\s*=\s*"[^"]*\p{Lu}\p{Ll}+ [^"]*""""),
                Regex("""->\s*"[^"$]*\p{Lu}\p{Ll}[^"]*""""),
                Regex("""\breturn\s+"[^"]*\p{L}+ \p{L}[^"]*""""),
                Regex(""""[^"]*\$\{?\w+(\.\w+)*\}? \p{Ll}+[^"]*""""),
                Regex("""\belse\s+$PHRASE"""),
                Regex("""\?:\s*$PHRASE"""),
                // A line that is only a literal: an argument, or part of a sentence joined with `+`.
                Regex("""^"(?=[^"]*\p{Ll}{2})[^"$%`]* [^"$%`]*"[\s,)+]*$"""),
                Regex(""",\s*"\p{Lu}\p{Ll}+( \p{L}+)+"\s*[,)]"""),
                Regex("""\bIcons\.[\w.]+,\s*$WORDS"""),
                Regex("""\bmakeText\(\s*\w+,\s*$WORDS"""),
                Regex("""\bCustomAccessibilityAction\(\s*$WORDS"""),
                // A capitalised phrase anywhere; a literal with a % format is a log's.
                Regex(""""\p{Lu}\p{Ll}+ [^"%]*""""),
                // A lambda that yields words: a fallback, or text built per item.
                Regex("""\{\s*"\p{Lu}\p{Ll}[^"]*"\s*\}"""),
                // Two values joined by a separator, whose order a language may change.
                Regex(""""\$\{?\w+(\.\w+)*\}? [·•—–-] \$"""),
            )

        /** A line that is the level of a log call chained under `Timber.tag(…)`. */
        val LOG_LEVEL = Regex("""^\.[vdiwe]\(""")

        /** Sources with no UI text in them: the database layer's SQL. */
        val NOT_UI = listOf("/data/db/")

        /**
         * Lines that are about the code, not for the user: logs, SQL,
         * preconditions. An exception's message is not among them: one
         * carried by `ApiResult.Unexpected` is shown, inside "Something went
         * wrong: …". One that never is says so with `i18n-ignore`.
         */
        val SKIP = listOf("Timber", "Log.", "@Query", "require(", "check(", "error(\"", "TODO(", "@SerialName")
    }
}
