package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MarkdownToolbarActions] — the editor toolbar's index
 * arithmetic.
 *
 * This is pure, cheap-to-test code that had no tests at all, and it is the
 * code standing between the user and their unsaved page: every action here
 * calls `substring` on offsets it computes itself, so an off-by-one is a
 * `StringIndexOutOfBoundsException` inside a `BasicTextField` callback, and
 * the whole buffer goes with it. The awkward inputs — empty text, a caret at
 * offset 0, a caret at the end, a leading newline, a selection covering
 * everything — are therefore the point of this file rather than an
 * afterthought.
 */
class MarkdownToolbarActionsTest {
    private fun editor(
        text: String,
        start: Int = text.length,
        end: Int = start,
    ) = TextFieldValue(text = text, selection = TextRange(start, end))

    // ---------------------------------------------------------------- bounds

    @Test
    fun lineBounds_emptyText_isTheEmptyBlockAtZero() {
        assertEquals(0 to 0, MarkdownToolbarActions.lineBounds("", 0, 0))
    }

    @Test
    fun lineBounds_caretAtZeroBeforeALeadingNewline_isTheEmptyFirstLine() {
        // The regression: coercing `min - 1` up to 0 made `lastIndexOf` find
        // the newline *at* 0, reporting a line that started at 1 and ended at
        // 0 — `substring(1, 0)`.
        assertEquals(0 to 0, MarkdownToolbarActions.lineBounds("\nabc", 0, 0))
        assertEquals(0 to 0, MarkdownToolbarActions.lineBounds("\n", 0, 0))
    }

    @Test
    fun lineBounds_spansWholeLinesTouchedByTheSelection() {
        assertEquals(0 to 3, MarkdownToolbarActions.lineBounds("abc", 0, 3))
        assertEquals(0 to 1, MarkdownToolbarActions.lineBounds("a\nb", 1, 1))
        assertEquals(2 to 3, MarkdownToolbarActions.lineBounds("a\nb", 2, 2))
        assertEquals(4 to 13, MarkdownToolbarActions.lineBounds("one\ntwo\nthree", 5, 9))
    }

    @Test
    fun lineBounds_offsetsPastTheEnd_areClamped() {
        // A selection can outlive the text it was measured against — the
        // editor restores a caret onto a body that arrives separately.
        assertEquals(0 to 3, MarkdownToolbarActions.lineBounds("abc", 99, 120))
        assertEquals(0 to 0, MarkdownToolbarActions.lineBounds("", -5, 7))
    }

    @Test
    fun lineBounds_neverReturnsAnInvertedRange() {
        val texts = listOf("", "\n", "\n\n", "a", "a\n", "\na", "a\nb\n", "one\ntwo\nthree")
        for (text in texts) {
            for (min in -1..text.length + 1) {
                for (max in min..text.length + 1) {
                    val (start, end) = MarkdownToolbarActions.lineBounds(text, min, max)
                    assertTrue(
                        "lineBounds(\"$text\", $min, $max) = $start..$end",
                        start in 0..end && end <= text.length,
                    )
                }
            }
        }
    }

    // --------------------------------------------------- the crash, per action

    @Test
    fun heading_caretAtZeroOfALeadingNewline_doesNotThrow() {
        val result = MarkdownToolbarActions.heading(editor("\nabc", start = 0))
        assertEquals("# \nabc", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun bullet_caretAtZeroOfALeadingNewline_doesNotThrow() {
        assertEquals("- \nabc", MarkdownToolbarActions.bullet(editor("\nabc", start = 0)).text)
    }

    @Test
    fun numbered_caretAtZeroOfALeadingNewline_doesNotThrow() {
        assertEquals("1. \nabc", MarkdownToolbarActions.numbered(editor("\nabc", start = 0)).text)
    }

    @Test
    fun checklist_caretAtZeroOfALeadingNewline_doesNotThrow() {
        assertEquals("- [ ] \nabc", MarkdownToolbarActions.checklist(editor("\nabc", start = 0)).text)
    }

    // ------------------------------------------------------------ empty buffer

    @Test
    fun linePrefixes_onAnEmptyBuffer_insertTheMarkerAndParkTheCaretAfterIt() {
        val heading = MarkdownToolbarActions.heading(editor(""))
        assertEquals("# ", heading.text)
        assertEquals(TextRange(2), heading.selection)
        assertEquals("- ", MarkdownToolbarActions.bullet(editor("")).text)
        assertEquals("1. ", MarkdownToolbarActions.numbered(editor("")).text)
        assertEquals("- [ ] ", MarkdownToolbarActions.checklist(editor("")).text)
    }

    // ------------------------------------------------------------- caret cases

    @Test
    fun heading_caretAtEndOfText_prefixesThatLine() {
        val result = MarkdownToolbarActions.heading(editor("abc", start = 3))
        assertEquals("# abc", result.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun heading_caretOnTheLastOfSeveralLines_leavesTheOthersAlone() {
        val result = MarkdownToolbarActions.heading(editor("one\ntwo", start = 7))
        assertEquals("one\n# two", result.text)
    }

    @Test
    fun bullet_caretOnAnEmptyTrailingLine_prefixesTheEmptyLine() {
        // `text.length` sits immediately after a trailing newline: the line is
        // empty and both bounds land on `length`.
        assertEquals("one\n- ", MarkdownToolbarActions.bullet(editor("one\n", start = 4)).text)
    }

    // -------------------------------------------------------- whole-text select

    @Test
    fun heading_fullySelectedText_prefixesEveryLine() {
        val result = MarkdownToolbarActions.heading(editor("abc\ndef", start = 0, end = 7))
        assertEquals("# abc\n# def", result.text)
        assertEquals(TextRange(11), result.selection)
    }

    @Test
    fun checklist_fullySelectedText_prefixesEveryLine() {
        val result = MarkdownToolbarActions.checklist(editor("a\nb\nc", start = 0, end = 5))
        assertEquals("- [ ] a\n- [ ] b\n- [ ] c", result.text)
    }

    // ------------------------------------------------------ multi-line selects

    @Test
    fun numbered_multiLineSelection_numbersFromOneAndKeepsUntouchedLines() {
        // Selection starts inside "two" and ends inside "three": both whole
        // lines are rewritten, "one" isn't.
        val result = MarkdownToolbarActions.numbered(editor("one\ntwo\nthree", start = 5, end = 9))
        assertEquals("one\n1. two\n2. three", result.text)
    }

    @Test
    fun bullet_selectionEndingExactlyOnANewline_doesNotSwallowTheNextLine() {
        val result = MarkdownToolbarActions.bullet(editor("one\ntwo", start = 0, end = 3))
        assertEquals("- one\ntwo", result.text)
    }

    // ------------------------------------------------------------ toggles

    @Test
    fun heading_cyclesThroughTheThreeLevelsAndBackToPlain() {
        var value = editor("a", start = 1)
        value = MarkdownToolbarActions.heading(value)
        assertEquals("# a", value.text)
        value = MarkdownToolbarActions.heading(value)
        assertEquals("## a", value.text)
        value = MarkdownToolbarActions.heading(value)
        assertEquals("### a", value.text)
        value = MarkdownToolbarActions.heading(value)
        assertEquals("a", value.text)
    }

    @Test
    fun bullet_isAToggle() {
        val on = MarkdownToolbarActions.bullet(editor("a"))
        assertEquals("- a", on.text)
        assertEquals("a", MarkdownToolbarActions.bullet(on).text)
    }

    @Test
    fun numbered_stripsAnExistingNumberRegardlessOfItsValue() {
        assertEquals("a", MarkdownToolbarActions.numbered(editor("12. a")).text)
    }

    @Test
    fun checklist_goesUncheckedThenCheckedThenGone() {
        var value = editor("a")
        value = MarkdownToolbarActions.checklist(value)
        assertEquals("- [ ] a", value.text)
        value = MarkdownToolbarActions.checklist(value)
        assertEquals("- [x] a", value.text)
        value = MarkdownToolbarActions.checklist(value)
        assertEquals("a", value.text)
    }

    // ------------------------------------------------------------ inline wraps

    @Test
    fun bold_wrapsTheSelectionAndKeepsItSelected() {
        val result = MarkdownToolbarActions.bold(editor("abc", start = 0, end = 3))
        assertEquals("**abc**", result.text)
        assertEquals(TextRange(2, 5), result.selection)
    }

    @Test
    fun italic_onAnEmptyBuffer_leavesTheCaretBetweenTheSigils() {
        val result = MarkdownToolbarActions.italic(editor(""))
        assertEquals("__", result.text)
        assertEquals(TextRange(1), result.selection)
    }

    @Test
    fun inlineCode_atTheEndOfText_appendsAnEmptyPair() {
        assertEquals("abc``", MarkdownToolbarActions.inlineCode(editor("abc")).text)
    }

    @Test
    fun link_withNoSelection_insertsAPlaceholderAndParksTheCaretInsideTheUrl() {
        val result = MarkdownToolbarActions.link(editor(""))
        assertEquals("[link](https://)", result.text)
        // Immediately before the closing paren, ready for typing the URL.
        assertEquals(TextRange("[link](https://".length), result.selection)
    }

    @Test
    fun link_withASelection_keepsTheSelectedTextAsTheLabel() {
        assertEquals(
            "[abc](https://)",
            MarkdownToolbarActions.link(editor("abc", start = 0, end = 3)).text,
        )
    }

    // ----------------------------------------------------------- attachments

    @Test
    fun insertAttachment_embedsImagesAndLinksEverythingElse() {
        assertEquals(
            "![shot.png](shot.png)",
            MarkdownToolbarActions.insertAttachment(editor(""), "shot.png").text,
        )
        assertEquals(
            "[notes.pdf](notes.pdf)",
            MarkdownToolbarActions.insertAttachment(editor(""), "notes.pdf").text,
        )
    }

    @Test
    fun insertAttachment_replacesTheSelection() {
        val result = MarkdownToolbarActions.insertAttachment(
            editor("a  b", start = 1, end = 3),
            "shot.png",
        )
        assertEquals("a![shot.png](shot.png)b", result.text)
        assertEquals(TextRange("a![shot.png](shot.png)".length), result.selection)
    }

    // ------------------------------------------------------- stale selections

    @Test
    fun everyAction_survivesASelectionPastTheEndOfTheText() {
        // TextFieldValue doesn't validate its selection against its text, and
        // the editor can hand us a caret restored from an older buffer. None
        // of these may throw.
        val stale = TextFieldValue(text = "ab", selection = TextRange(40, 60))
        assertEquals("ab****", MarkdownToolbarActions.bold(stale).text)
        assertEquals("ab[link](https://)", MarkdownToolbarActions.link(stale).text)
        assertEquals("# ab", MarkdownToolbarActions.heading(stale).text)
        assertEquals("- ab", MarkdownToolbarActions.bullet(stale).text)
        assertEquals("1. ab", MarkdownToolbarActions.numbered(stale).text)
        assertEquals("- [ ] ab", MarkdownToolbarActions.checklist(stale).text)
        assertEquals(
            "ab![shot.png](shot.png)",
            MarkdownToolbarActions.insertAttachment(stale, "shot.png").text,
        )
    }

    @Test
    fun linePrefixes_surviveAReversedSelection() {
        // `TextRange(end, start)` — dragging a selection backwards.
        val reversed = TextFieldValue(text = "one\ntwo", selection = TextRange(7, 5))
        assertEquals("one\n- two", MarkdownToolbarActions.bullet(reversed).text)
    }
}
