package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Batch 31 dialect transforms ([rewriteCallouts],
 * [rewriteHighlights]). Cases focus on:
 *  - happy-path replacement for each callout type and for `==text==`,
 *  - **fence / inline-code skipping** — both transforms must leave
 *    code samples that use the same syntax untouched,
 *  - degenerate inputs (empty, no markers, unknown callout types).
 *
 * Regression net: these transforms run on every page body before
 * Markwon sees it.
 */
class TextDialectExtensionsTest {
    // --- Callouts ---

    @Test
    fun rewriteCallouts_infoCallout_rewritesFirstLine() {
        val input =
            """
            > [!INFO]
            > Useful information.
            """.trimIndent()
        val out = rewriteCallouts(input)
        assertEquals(
            """
            > ℹ️ **Info**
            > Useful information.
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun rewriteCallouts_warnAndError_eachGetCorrectEmoji() {
        assertTrue(rewriteCallouts("> [!WARN]").contains("⚠️ **Warning**"))
        assertTrue(rewriteCallouts("> [!WARNING]").contains("⚠️ **Warning**"))
        assertTrue(rewriteCallouts("> [!ERROR]").contains("❌ **Error**"))
        assertTrue(rewriteCallouts("> [!SUCCESS]").contains("✅ **Success**"))
    }

    @Test
    fun rewriteCallouts_lowercaseType_caseInsensitiveMatch() {
        assertTrue(rewriteCallouts("> [!info]").contains("ℹ️ **Info**"))
    }

    @Test
    fun rewriteCallouts_unknownType_passesThrough() {
        // Unknown callout markers should pass through verbatim so the
        // user at least sees the original source.
        val input = "> [!CUSTOM]\n> body"
        assertEquals(input, rewriteCallouts(input))
    }

    @Test
    fun rewriteCallouts_insideFencedCode_doesNotRewrite() {
        val input =
            """
            text
            ```
            > [!INFO]
            > don't touch this
            ```
            more
            """.trimIndent()
        val out = rewriteCallouts(input)
        assertTrue("Callout marker inside fence should survive verbatim", out.contains("> [!INFO]"))
        assertTrue(out.contains("```"))
    }

    @Test
    fun rewriteCallouts_emptyInput_returnsEmpty() {
        assertEquals("", rewriteCallouts(""))
    }

    @Test
    fun rewriteCallouts_preservesTrailingNewline() {
        val input = "hello\n"
        val out = rewriteCallouts(input)
        assertEquals("hello\n", out)
    }

    @Test
    fun rewriteCallouts_noTrailingNewline_stayBare() {
        val input = "hello"
        val out = rewriteCallouts(input)
        assertEquals("hello", out)
    }

    // --- Highlight ---

    @Test
    fun rewriteHighlights_basicWrap_emitsMarkTag() {
        assertEquals("<mark>hello</mark>", rewriteHighlights("==hello=="))
    }

    @Test
    fun rewriteHighlights_inlineCode_isSkipped() {
        // `==x==` inside a backtick span is a literal code sample,
        // not a highlight — pass through.
        val input = "Use `==text==` for highlight."
        assertEquals(input, rewriteHighlights(input))
    }

    @Test
    fun rewriteHighlights_fencedCode_isSkipped() {
        val input =
            """
            ```
            ==don't==
            ```
            """.trimIndent()
        val out = rewriteHighlights(input)
        assertTrue(out.contains("==don't=="))
        assertTrue(!out.contains("<mark>"))
    }

    @Test
    fun rewriteHighlights_emptyMarker_doesNotMatch() {
        // `====` is rejected by the minimum-one-char inner pattern.
        assertEquals("====", rewriteHighlights("===="))
    }

    @Test
    fun rewriteHighlights_multipleOnSameLine_eachWrapped() {
        assertEquals(
            "<mark>a</mark> and <mark>b</mark>",
            rewriteHighlights("==a== and ==b=="),
        )
    }

    @Test
    fun rewriteHighlights_noMarkers_returnsInputVerbatim() {
        val input = "plain text\nno highlights here"
        assertEquals(input, rewriteHighlights(input))
    }

    // --- Footnotes ---

    @Test
    fun rewriteFootnotes_refAndDefinition_supAndSection() {
        val input =
            """
            As noted[^1] here.

            [^1]: The source.
            """.trimIndent()
        val out = rewriteFootnotes(input)
        assertTrue("ref becomes superscript", out.contains("As noted<sup>1</sup> here."))
        assertTrue("footnotes section appended", out.contains("**Footnotes**"))
        assertTrue("definition listed", out.contains("1. The source."))
        assertFalse("definition line lifted out of body", out.contains("[^1]: The source."))
    }

    @Test
    fun rewriteFootnotes_numbersByDefinitionOrder() {
        val input =
            """
            First[^a] then second[^b].

            [^a]: Alpha.
            [^b]: Beta.
            """.trimIndent()
        val out = rewriteFootnotes(input)
        assertTrue(out.contains("First<sup>1</sup> then second<sup>2</sup>."))
        assertTrue(out.contains("1. Alpha."))
        assertTrue(out.contains("2. Beta."))
    }

    @Test
    fun rewriteFootnotes_orphanReference_passesThrough() {
        // A ref with no matching definition is left verbatim, and with
        // no definitions at all the whole input is returned unchanged.
        val input = "Dangling[^x] ref with no definition."
        assertEquals(input, rewriteFootnotes(input))
    }

    @Test
    fun rewriteFootnotes_indentedContinuation_foldedIntoText() {
        val input =
            """
            Ref[^1].

            [^1]: First line
              continued line.
            """.trimIndent()
        val out = rewriteFootnotes(input)
        assertTrue(out.contains("1. First line continued line."))
        assertFalse("indented continuation line lifted out of body", out.contains("\n  continued line."))
    }

    @Test
    fun rewriteFootnotes_insideFence_notRewritten() {
        val input =
            """
            ```
            look up[^1] in
            [^1]: not a real definition
            ```
            """.trimIndent()
        // Everything is inside a fence: no definition is extracted, so
        // the body (including the literal `[^1]`) is returned verbatim.
        assertEquals(input, rewriteFootnotes(input))
    }

    @Test
    fun rewriteFootnotes_refInInlineCode_notRewritten() {
        val input =
            """
            Use `[^1]` literally[^1].

            [^1]: The note.
            """.trimIndent()
        val out = rewriteFootnotes(input)
        assertTrue("inline-code ref survives", out.contains("`[^1]`"))
        assertTrue("prose ref rewritten", out.contains("literally<sup>1</sup>."))
    }

    @Test
    fun rewriteFootnotes_noMarkers_returnsInputVerbatim() {
        val input = "plain text\nno footnotes here"
        assertEquals(input, rewriteFootnotes(input))
    }
}
