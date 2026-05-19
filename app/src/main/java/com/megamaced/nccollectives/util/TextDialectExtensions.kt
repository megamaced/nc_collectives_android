package com.megamaced.nccollectives.util

// Pre-processors that rewrite Nextcloud-Text-specific markdown
// extensions into shapes Markwon already renders. Lets the native
// viewer close the dialect gap against what the web Collectives /
// Text editor displays without needing a full custom CommonMark
// extension per feature.
//
// The transforms are applied to the raw markdown body *before* it
// reaches Markwon. Fenced code blocks (` ``` `, `~~~`) and inline
// code spans are skipped so the transforms don't mangle code samples
// that happen to use the same syntax — same posture as
// `MarkdownLinkResolver.expandWikilinks`.
//
// Closes Batch 31b (callouts) + 31c (highlight). 31a (multi-line
// tables), 31d (underline `__text__` — collides with CommonMark
// bold), 31e (math), 31f (mentions) are intentionally deferred —
// see the wiki page for the per-item Skip rationale.

/**
 * Rewrites Nextcloud Text callout / admonition syntax into a styled
 * blockquote prefix that Markwon renders natively.
 *
 * Input shape (markdown-it-callouts, used by upstream Text — see
 * `nextcloud/text/src/markdownit/callouts.js`):
 *
 * ```
 * > [!INFO]
 * > Useful information.
 * ```
 *
 * Output:
 *
 * ```
 * > ℹ️ **Info**
 * > Useful information.
 * ```
 *
 * Markwon then renders the whole block as a normal blockquote with
 * the emoji+label header. Not as pretty as a tone-tinted card (which
 * would need a custom CommonMark BlockParser), but readable and
 * preserves the document's meaning. Cheap and reversible if a future
 * batch wants to upgrade to real cards.
 *
 * Recognised types match upstream's set: `INFO`, `WARN` (or
 * `WARNING`), `ERROR`, `SUCCESS`. Unknown `[!X]` markers pass
 * through verbatim so the user at least sees the original.
 */
internal fun rewriteCallouts(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    val out = StringBuilder(markdown.length)
    var inFence = false
    // Track \n preservation explicitly — `lines()` drops trailing
    // newlines and we don't want to add or remove blanks that affect
    // CommonMark block parsing downstream.
    val keepTrailingNewline = markdown.endsWith('\n')
    val sourceLines = markdown.lines()
    sourceLines.forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        val isFenceMarker = trimmed.startsWith("```") || trimmed.startsWith("~~~")
        if (isFenceMarker) {
            inFence = !inFence
            out.append(line)
        } else if (!inFence) {
            val match = CALLOUT_REGEX.matchEntire(line)
            if (match != null) {
                val type = match.groupValues[1].uppercase()
                val (emoji, label) = labelFor(type)
                // Preserve any leading whitespace before the `>` so
                // nested callouts (rare) still parse as nested
                // blockquotes downstream.
                val leading = line.substringBefore('>', "")
                out
                    .append(leading)
                    .append("> ")
                    .append(emoji)
                    .append(' ')
                    .append("**")
                    .append(label)
                    .append("**")
            } else {
                out.append(line)
            }
        } else {
            out.append(line)
        }
        // `lines()` splits on every `\n`, including the implicit
        // trailing one; re-emit a `\n` between every pair so the
        // round-trip is faithful.
        if (index < sourceLines.lastIndex) out.append('\n')
    }
    if (keepTrailingNewline && !out.endsWith('\n')) out.append('\n')
    return out.toString()
}

/**
 * Rewrites `==text==` highlight syntax (the `markdown-it-mark`
 * dialect upstream Text uses) into `<mark>text</mark>`. Markwon's
 * `HtmlPlugin` (wired in Batch 24) renders `<mark>` as a yellow
 * highlight by default — not perfectly M3-tinted but visually
 * obvious that the text is highlighted.
 *
 * The transform respects fenced code blocks and inline code spans
 * via the same alternation-regex pattern `expandWikilinks` uses:
 * earlier alternations win, so `==text==` inside a code span
 * passes through verbatim.
 */
internal fun rewriteHighlights(markdown: String): String {
    if (markdown.isEmpty() || "==" !in markdown) return markdown
    return HIGHLIGHT_PATTERN.replace(markdown) { match ->
        when {
            match.groups["fence"] != null -> match.value
            match.groups["code"] != null -> match.value
            match.groups["mark"] != null -> {
                val text = match.groups["text"]!!.value
                "<mark>$text</mark>"
            }
            else -> match.value
        }
    }
}

private fun labelFor(type: String): Pair<String, String> =
    when (type) {
        "INFO" -> "ℹ️" to "Info"
        "WARN", "WARNING" -> "⚠️" to "Warning"
        "ERROR" -> "❌" to "Error"
        "SUCCESS" -> "✅" to "Success"
        else -> "ℹ️" to type.lowercase().replaceFirstChar { it.uppercase() }
    }

private val CALLOUT_REGEX = Regex("^\\s*>\\s*\\[!(INFO|WARN|WARNING|ERROR|SUCCESS)]\\s*$", RegexOption.IGNORE_CASE)

// Same fence/code-first alternation shape as
// `MarkdownLinkResolver.WIKILINK_PATTERN`. The `text` group is
// non-greedy + disallows `\n` so a `==` that wraps across paragraphs
// isn't accidentally matched. Empty `====` is rejected by the
// minimum-one-char inner pattern.
private val HIGHLIGHT_PATTERN = Regex(
    "(?s)" +
        "(?<fence>```.*?```|~~~.*?~~~)" +
        "|(?<code>`[^`\\n]+`)" +
        "|(?<mark>==(?<text>[^=\\n][^\\n]*?)==)",
)
