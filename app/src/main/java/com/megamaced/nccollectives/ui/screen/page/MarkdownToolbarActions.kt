package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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
 */
internal object MarkdownToolbarActions {
    fun bold(value: TextFieldValue): TextFieldValue = wrap(value, "**")

    fun italic(value: TextFieldValue): TextFieldValue = wrap(value, "_")

    fun inlineCode(value: TextFieldValue): TextFieldValue = wrap(value, "`")

    fun link(value: TextFieldValue): TextFieldValue {
        val (text, selection) = value.text to value.selection
        val selected = text.substring(selection.min, selection.max)
        val replacement = "[${selected.ifEmpty { "link" }}](https://)"
        val newText = text.replaceRange(selection.min, selection.max, replacement)
        val cursor = selection.min + replacement.length - 1 // place cursor inside the URL parens
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
            val pattern = Regex("^\\d+\\.\\s")
            if (pattern.containsMatchIn(line)) line.replaceFirst(pattern, "") else "${idx + 1}. $line"
        }
    }

    fun checklist(value: TextFieldValue): TextFieldValue =
        lineMutate(value) { line ->
            when {
                line.startsWith("- [ ] ") -> "- [x] " + line.removePrefix("- [ ] ")
                line.startsWith("- [x] ") -> line.removePrefix("- [x] ")
                else -> "- [ ] $line"
            }
        }

    private fun wrap(
        value: TextFieldValue,
        sigil: String,
    ): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val before = text.substring(0, sel.min)
        val middle = text.substring(sel.min, sel.max)
        val after = text.substring(sel.max)
        val newText = before + sigil + middle + sigil + after
        val newSelection = TextRange(sel.min + sigil.length, sel.max + sigil.length)
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
        val sel = value.selection
        val startLine = text.lastIndexOf('\n', (sel.min - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val endLine = text.indexOf('\n', sel.max).let { if (it < 0) text.length else it }
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
