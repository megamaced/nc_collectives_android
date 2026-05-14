package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [expandWikilinks] and [decodeWikiTarget].
 *
 * Closes S-7 from the Batch 17 audit (regex inner-bracket edge case) and
 * the fenced-code-block / inline-code skipping guarantee introduced in
 * Batch 18c (B-4). Markwon's `HtmlPlugin` isn't installed in our build
 * so none of the cases below are exploitable for HTML injection — these
 * tests pin the existing behaviour as a regression net.
 */
class MarkdownLinkResolverTest {
    @Test
    fun expandWikilinks_basicTarget_rewritesToLink() {
        assertEquals(
            "[Page Name](Page%20Name)",
            expandWikilinks("[[Page Name]]"),
        )
    }

    @Test
    fun expandWikilinks_pipedAlias_keepsAliasLabel() {
        assertEquals(
            "[Alias](Target%20Page)",
            expandWikilinks("[[Target Page|Alias]]"),
        )
    }

    @Test
    fun expandWikilinks_escapedOpener_isLeftAlone() {
        // `\[[Page]]` should not rewrite — the leading backslash escapes.
        assertEquals(
            "\\[[Page]]",
            expandWikilinks("\\[[Page]]"),
        )
    }

    @Test
    fun expandWikilinks_insideFencedCodeBlock_isLeftAlone() {
        val input =
            """
            Hello
            ```
            [[ExampleWiki]]
            ```
            World
            """.trimIndent()
        // The wikilink inside the fence must round-trip verbatim.
        val output = expandWikilinks(input)
        assertEquals(true, output.contains("[[ExampleWiki]]"))
        assertEquals(false, output.contains("(ExampleWiki)"))
    }

    @Test
    fun expandWikilinks_insideInlineCode_isLeftAlone() {
        val input = "Use `[[Page Name]]` syntax to link."
        val output = expandWikilinks(input)
        assertEquals(true, output.contains("`[[Page Name]]`"))
    }

    @Test
    fun expandWikilinks_multipleOnSameLine_allRewritten() {
        assertEquals(
            "[A](A) and [B](B)",
            expandWikilinks("[[A]] and [[B]]"),
        )
    }

    @Test
    fun expandWikilinks_emptyTarget_isLeftAlone() {
        // Regex requires at least one non-bracket char before `]]`.
        assertEquals(
            "[[]]",
            expandWikilinks("[[]]"),
        )
    }

    @Test
    fun decodeWikiTarget_stripsLeadingSlashAndMdExtension() {
        assertEquals("Page Name", decodeWikiTarget("./Page%20Name.md"))
        assertEquals("Page Name", decodeWikiTarget("/Page%20Name.md"))
        assertEquals("Page Name", decodeWikiTarget("Page%20Name.MD"))
    }

    @Test
    fun decodeWikiTarget_stripsQueryAndFragment() {
        assertEquals("Target", decodeWikiTarget("Target?ref=foo"))
        assertEquals("Target", decodeWikiTarget("Target#section"))
    }
}
