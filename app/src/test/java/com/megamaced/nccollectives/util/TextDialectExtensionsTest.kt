package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
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
}
