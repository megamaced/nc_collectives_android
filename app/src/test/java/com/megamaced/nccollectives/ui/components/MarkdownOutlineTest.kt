package com.megamaced.nccollectives.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownOutlineTest {
    @Test
    fun `sorts spans into document order`() {
        // `Spanned.getSpans` makes no ordering guarantee, so the index has
        // to impose one or it lists the page out of sequence.
        val outline = outlineOf(
            listOf(
                RawHeading(level = 2, start = 90, end = 97, text = "Later\n\n"),
                RawHeading(level = 1, start = 0, end = 6, text = "First\n"),
                RawHeading(level = 3, start = 40, end = 47, text = "Middle\n"),
            ),
        )

        assertEquals(listOf("First", "Middle", "Later"), outline.map { it.title })
        assertEquals(listOf(0, 40, 90), outline.map { it.charOffset })
    }

    @Test
    fun `trims the trailing newline the heading span carries`() {
        val outline = outlineOf(listOf(RawHeading(level = 1, start = 0, end = 13, text = "  Overview \n")))

        assertEquals("Overview", outline.single().title)
    }

    @Test
    fun `drops a heading that renders to nothing`() {
        // `##` alone on a line: a real heading node with no text behind it.
        // Listing it would put a blank, tappable row in the index.
        val outline = outlineOf(
            listOf(
                RawHeading(level = 2, start = 0, end = 1, text = "\n"),
                RawHeading(level = 2, start = 10, end = 16, text = "Real\n"),
            ),
        )

        assertEquals(listOf("Real"), outline.map { it.title })
    }

    @Test
    fun `drops a detached span`() {
        // -1 is what `getSpanStart` returns for a span the renderer no longer
        // has attached; it would otherwise scroll to a nonsense offset.
        val outline = outlineOf(listOf(RawHeading(level = 1, start = -1, end = -1, text = "Ghost")))

        assertTrue(outline.isEmpty())
    }

    @Test
    fun `clamps an out-of-range level`() {
        val outline = outlineOf(
            listOf(
                RawHeading(level = 0, start = 0, end = 4, text = "Low"),
                RawHeading(level = 9, start = 10, end = 15, text = "High"),
            ),
        )

        assertEquals(listOf(1, 6), outline.map { it.level })
    }

    @Test
    fun `keeps repeated heading text as distinct entries`() {
        // Two "Notes" sections are ordinary. They must stay two rows, which
        // is why the sheet keys on position rather than on the title.
        val outline = outlineOf(
            listOf(
                RawHeading(level = 2, start = 0, end = 6, text = "Notes\n"),
                RawHeading(level = 2, start = 50, end = 56, text = "Notes\n"),
            ),
        )

        assertEquals(2, outline.size)
        assertEquals(listOf(0, 50), outline.map { it.charOffset })
    }
}
