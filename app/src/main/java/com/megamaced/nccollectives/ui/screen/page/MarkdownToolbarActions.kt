package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.megamaced.nccollectives.util.isImageFileName

/**
 * Pure helpers that compute the next [TextFieldValue] for each toolbar
 * action. They preserve / round-trip selected text wherever sensible:
 *
 *  - inline wraps (bold, italic, code, link) surround the selection with
 *    a sigil pair and leave the cursor inside or just after the wrap
 *  - line prefixes (heading, bullet, numbered, checklist) operate on every
 *    line touched by the selection
 *
 * No Compose APIs, no side effects — pure functions, easy to unit-test.
 *
 * B-72: every action here indexes into the buffer, and an off-by-one is a
 * `StringIndexOutOfBoundsException` that takes the user's unsaved page with
 * it. Two rules hold throughout: offsets come from [clampedSelection] (a
 * selection can outlive the text it was measured against), and the line
 * block comes from [lineBounds] rather than open-coded `lastIndexOf`
 * arithmetic.
 */
internal object MarkdownToolbarActions {
    fun bold(value: TextFieldValue): TextFieldValue = wrap(value, "**")

    fun italic(value: TextFieldValue): TextFieldValue = wrap(value, "_")

    fun inlineCode(value: TextFieldValue): TextFieldValue = wrap(value, "`")

    fun link(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val (min, max) = value.clampedSelection()
        val selected = text.substring(min, max)
        val replacement = "[${selected.ifEmpty { "link" }}](https://)"
        val newText = text.replaceRange(min, max, replacement)
        val cursor = min + replacement.length - 1 // place cursor inside the URL parens
        return value.copy(text = newText, selection = TextRange(cursor))
    }

    fun heading(value: TextFieldValue): TextFieldValue =
        lineMutate(value) { line ->
            when {
                line.startsWith("### ") -> line.removePrefix("### ")
                line.startsWith("## ") -> "### " + line.removePrefix("## ")
                line.startsWith("# ") -> "## " + line.removePrefix("# ")
                else -> "# $line"
            }
        }

    fun bullet(value: TextFieldValue): TextFieldValue =
        lineMutate(value) { line ->
            if (line.startsWith("- ")) line.removePrefix("- ") else "- $line"
        }

    fun numbered(value: TextFieldValue): TextFieldValue {
        // For multi-line selections, number sequentially starting from 1.
        return lineMutateIndexed(value) { idx, line ->
            if (NUMBERED_PREFIX.containsMatchIn(line)) {
                line.replaceFirst(NUMBERED_PREFIX, "")
            } else {
                "${idx + 1}. $line"
            }
        }
    }

    /**
     * Inserts a reference to the attachment [fileName] at the cursor. The
     * URL part is the bare filename — `MarkdownView` resolves it against the
     * page's `.attachments.<pageId>/` directory at render time.
     *
     * Images get embed syntax (`![…](…)`); anything else gets a plain link,
     * because an embed pointing at a PDF renders as a blank slot where
     * Markwon failed to decode it as a bitmap. `MarkdownView` demotes such
     * embeds on the render path too (`demoteNonImageEmbeds`) — this just
     * avoids writing one in the first place.
     */
    fun insertAttachment(
        value: TextFieldValue,
        fileName: String,
    ): TextFieldValue {
        val text = value.text
        val (min, max) = value.clampedSelection()
        val before = text.substring(0, min)
        val after = text.substring(max)
        val snippet = if (isImageFileName(fileName)) {
            "![$fileName]($fileName)"
        } else {
            "[$fileName]($fileName)"
        }
        val newText = before + snippet + after
        return value.copy(text = newText, selection = TextRange(min + snippet.length))
    }

    fun checklist(value: TextFieldValue): TextFieldValue =
        lineMutate(value) { line ->
            when {
                line.startsWith("- [ ] ") -> "- [x] " + line.removePrefix("- [ ] ")
                line.startsWith("- [x] ") -> line.removePrefix("- [x] ")
                else -> "- [ ] $line"
            }
        }

    /**
     * Selection offsets, in ascending order, guaranteed to be valid indices
     * into `text`.
     *
     * A [TextFieldValue]'s selection can outlive the text it was measured
     * against — the editor restores a saved cursor onto a body that arrives
     * separately from the ViewModel (B-71), and the toolbar can fire on that
     * first frame. `substring` throws on a stale offset, so nothing in here
     * reads `value.selection` directly.
     */
    private fun TextFieldValue.clampedSelection(): Pair<Int, Int> {
        val min = selection.min.coerceIn(0, text.length)
        val max = selection.max.coerceIn(min, text.length)
        return min to max
    }

    /**
     * Offsets of the block of whole lines the selection [selMin]..[selMax]
     * touches — start inclusive, end exclusive.
     *
     * B-72: `selMin == 0` has to short-circuit. The previous
     * `lastIndexOf('\n', (selMin - 1).coerceAtLeast(0))` coerced -1 up to 0,
     * which made `lastIndexOf` search *from* index 0 — so a buffer starting
     * with a newline reported the line as starting at 1 while its end was
     * still 0, and `substring(1, 0)` threw. Every line-prefix action
     * (heading, bullet, numbered, checklist) came through here, so the crash
     * took the whole unsaved buffer with it.
     */
    internal fun lineBounds(
        text: String,
        selMin: Int,
        selMax: Int,
    ): Pair<Int, Int> {
        val min = selMin.coerceIn(0, text.length)
        val max = selMax.coerceIn(min, text.length)
        // `lastIndexOf` returning -1 (no preceding newline) already yields 0.
        val start = if (min == 0) 0 else text.lastIndexOf('\n', min - 1) + 1
        val end = text.indexOf('\n', max).let { if (it < 0) text.length else it }
        // `end` can't precede `start` for an in-range selection; the coerce
        // is the belt to the argument's braces.
        return start to end.coerceAtLeast(start)
    }

    private fun wrap(
        value: TextFieldValue,
        sigil: String,
    ): TextFieldValue {
        val text = value.text
        val (min, max) = value.clampedSelection()
        val before = text.substring(0, min)
        val middle = text.substring(min, max)
        val after = text.substring(max)
        val newText = before + sigil + middle + sigil + after
        val newSelection = TextRange(min + sigil.length, max + sigil.length)
        return value.copy(text = newText, selection = newSelection)
    }

    private fun lineMutate(
        value: TextFieldValue,
        mutate: (String) -> String,
    ): TextFieldValue = lineMutateIndexed(value) { _, line -> mutate(line) }

    private fun lineMutateIndexed(
        value: TextFieldValue,
        mutate: (Int, String) -> String,
    ): TextFieldValue {
        val text = value.text
        val (min, max) = value.clampedSelection()
        val (startLine, endLine) = lineBounds(text, min, max)
        val block = text.substring(startLine, endLine)
        val newBlock = block.split('\n').mapIndexed(mutate).joinToString("\n")
        val newText = text.substring(0, startLine) + newBlock + text.substring(endLine)
        // Move the cursor to the end of the rewritten block — keeps focus
        // sensible without trying to preserve a complex selection mapping.
        return value.copy(
            text = newText,
            selection = TextRange(startLine + newBlock.length),
        )
    }
}

/** Hoisted out of [MarkdownToolbarActions.numbered] so the toolbar doesn't recompile it per line. */
private val NUMBERED_PREFIX = Regex("^\\d+\\.\\s")
