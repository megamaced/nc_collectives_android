package com.megamaced.nccollectives.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text the user reads belongs in string resources, where translators can
 * reach it. This scans the app's sources for the shapes hardcoded UI text
 * takes — a literal handed to `Text`, a label or content description, a
 * snackbar, an error, a `when` branch or `return` that yields a sentence —
 * and fails on any it finds that isn't either:
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

    /** Every match in the sources, as `path<TAB>trimmed line`, once per matching line. */
    private fun findings(): List<String> =
        SOURCES
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(SOURCES).invariantSeparatorsPath
                file
                    .readLines()
                    .map { it.trim() }
                    .filter { line -> line.isCode() && PATTERNS.any { it.containsMatchIn(line) } }
                    .map { "$path\t$it" }
            }.toList()

    private fun String.isCode(): Boolean =
        !startsWith("//") &&
            !startsWith("*") &&
            !startsWith("/*") &&
            "i18n-ignore" !in this &&
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

        val PATTERNS =
            listOf(
                Regex("""\bText\(\s*(text\s*=\s*)?$WORDS"""),
                Regex(
                    """\b(contentDescription|label|title|message|placeholder|supportingText|actionLabel|subtitle|caption)""" +
                        """\s*=\s*$WORDS""",
                ),
                Regex("""showSnackbar\(\s*(message\s*=\s*)?$WORDS"""),
                Regex("""UiText\.Raw\(\s*$WORDS"""),
                Regex("""\b[A-Z]\w*Error\(\s*$WORDS"""),
                Regex("""\bError\(\s*$WORDS"""),
                Regex("""const val [A-Z_]+\s*=\s*"[^"]*\p{Lu}\p{Ll}+ [^"]*""""),
                Regex("""->\s*"[^"$]*\p{Lu}\p{Ll}[^"]*""""),
                Regex("""\breturn\s+"[^"]*\p{L}+ \p{L}[^"]*""""),
                Regex(""""[^"]*\$\{?\w+(\.\w+)*\}? \p{Ll}+[^"]*""""),
            )

        /** Lines that are about the code, not for the user: logs, SQL, preconditions. */
        val SKIP = listOf("Timber", "Log.", "@Query", "require(", "check(", "error(\"", "TODO(", "@SerialName")
    }
}
