package com.megamaced.nccollectives.ui.screen.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [plainTextPreview], the markdown-to-snippet stripper behind
 * `LandingPageCard`.
 *
 * R-45 rewrote it three ways — the seven regexes moved to top-level
 * `val`s, the scan is capped to a prefix of the body, and the call is
 * `remember`ed. Only the cap can change output, so the cases here are
 * split into two halves:
 *  - **output contract**: headings, links, images, code fences, inline
 *    code, emphasis, lists and whitespace all strip exactly as before,
 *  - **cap safety**: a truncated body must never leak half-written
 *    markdown into the snippet, and must not blank the snippet out when
 *    the body opens with an oversized code block.
 */
class LandingPreviewTest {
    // --- Output contract (unchanged by R-45) ---

    @Test
    fun heading_and_bold_areStripped() {
        assertEquals(
            "Heading Some bold text.",
            plainTextPreview("# Heading\n\nSome **bold** text."),
        )
    }

    @Test
    fun link_keepsTextAndDropsImage() {
        val out = plainTextPreview(
            "See [the docs](https://example.com/x) and ![img](https://e/i.png) here.",
        )
        assertEquals("See the docs and here.", out)
    }

    @Test
    fun fencedCodeBlock_isDroppedEntirely() {
        val body =
            """
            Intro text.

            ```kotlin
            val x = 1
            ```

            Tail text.
            """.trimIndent()
        assertEquals("Intro text. Tail text.", plainTextPreview(body))
    }

    @Test
    fun inlineCode_isDropped() {
        assertEquals("Run now.", plainTextPreview("Run `npm test` now."))
    }

    @Test
    fun listBullets_andBlockquote_areStripped() {
        assertEquals("first item second item", plainTextPreview("- first item\n- second item"))
        assertEquals("quoted", plainTextPreview("> quoted"))
    }

    @Test
    fun emphasisMarkers_areStripped() {
        assertEquals("a b c d", plainTextPreview("*a* _b_ ~~c~~ **d**"))
    }

    @Test
    fun emptyBody_givesEmptySnippet() {
        assertEquals("", plainTextPreview(""))
        assertEquals("", plainTextPreview("   \n\n  "))
    }

    @Test
    fun repeatedCalls_agree() {
        // The hoisted `Regex` instances are shared across calls — a
        // stateful pattern would show up here first.
        val body = "## Title\n\nSome `code` and [a link](https://x/y)."
        assertEquals(plainTextPreview(body), plainTextPreview(body))
    }

    // --- Cap safety ---

    @Test
    fun bodyUnderTheCap_isScannedWhole() {
        // 1455 chars — below the 1500-char scan cap, so nothing is lost.
        val body = "# H\n\n" + "word ".repeat(290)
        val out = plainTextPreview(body)
        assertTrue("starts with the heading", out.startsWith("H word"))
        assertTrue("last word survives", out.endsWith("word"))
    }

    @Test
    fun longBody_keepsTheVisiblePrefix_andStopsScanning() {
        val body = "# Title\n\nFirst paragraph of the page.\n\n" + "filler word here\n".repeat(150)
        val out = plainTextPreview(body)
        // The two rendered lines come off the front of the body, so the
        // prefix the card actually shows is byte-identical to the
        // uncapped result.
        assertTrue(out.startsWith("Title First paragraph of the page. filler word here"))
        // …and the tail was never scanned: the uncapped strip would have
        // produced ~2500 characters.
        assertTrue("capped, not full-scanned: ${out.length}", out.length < 1600)
    }

    @Test
    fun capLandingMidLine_doesNotLeakHalfWrittenMarkdown() {
        // One very long line of links: the cap falls inside it, so the
        // trim-back-to-the-last-newline guard has to drop the whole line
        // rather than leave a truncated `[text](url` behind.
        val body = "Intro.\n" + "[link text](https://example.com/very/long/url) ".repeat(60)
        val out = plainTextPreview(body)
        assertEquals("Intro.", out)
        assertFalse("no literal link syntax", out.contains("["))
        assertFalse("no literal link syntax", out.contains("]("))
    }

    @Test
    fun bodyOpeningWithOversizedCodeBlock_stillShowsTheTextAfterIt() {
        // The cap lands inside the fence, leaving an opener with no
        // closer; blanking the snippet would be worse than paying for one
        // full scan, so the stripper falls back to the whole body.
        val body = "```\n" + "x".repeat(2000) + "\n```\nReal text after code."
        assertEquals("Real text after code.", plainTextPreview(body))
    }

    @Test
    fun capLandingInsideALateCodeBlock_doesNotLeakCode() {
        // Fence opens after ~1200 chars of prose and runs past the cap.
        val body = "Readable intro.\n" + "prose line here\n".repeat(75) + "```\n" + "secret".repeat(400)
        val out = plainTextPreview(body)
        assertTrue(out.startsWith("Readable intro. prose line here"))
        assertFalse("code contents must not leak", out.contains("secret"))
        assertFalse("fence marker must not leak", out.contains("```"))
    }
}
