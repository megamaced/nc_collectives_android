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
// Closes Batch 31b (callouts) + 31c (highlight) + footnotes (upstream
// Text added `markdown-it-footnote` in July 2026, lands v34.0.2+).
// 31a (multi-line tables), 31d (underline `__text__` — collides with
// CommonMark bold), 31e (math), 31f (mentions) are intentionally
// deferred — see the wiki page for the per-item Skip rationale.

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

/**
 * Rewrites Nextcloud Text footnote syntax (the `markdown-it-footnote`
 * dialect upstream Text added July 2026 — see `nextcloud/text`
 * `src/markdownit/index.js`, lands v34.0.2+) into a shape Markwon
 * renders natively:
 *
 *  - inline references `[^id]` become `<sup>N</sup>` superscripts
 *    (Markwon's `HtmlPlugin`, wired in Batch 24, renders `<sup>`), and
 *  - the `[^id]: text` definitions are lifted out of the body into a
 *    trailing "Footnotes" ordered list.
 *
 * ```
 * As noted[^1] elsewhere[^src].          As noted<sup>1</sup> elsewhere<sup>2</sup>.
 *                                  ─►
 * [^1]: First note.                      ---
 * [^src]: The source.                    **Footnotes**
 *                                        1. First note.
 *                                        2. The source.
 * ```
 *
 * Numbering follows **definition order**. Only references that resolve
 * to a definition are rewritten; an orphan `[^id]` with no matching
 * definition passes through verbatim (same "unknown passes through"
 * posture as [rewriteCallouts]). Definitions are matched at line start
 * (0–3 leading spaces, CommonMark tolerance); a simple indented
 * continuation line is folded into the definition text. Inline
 * footnotes (`^[text]`) and multi-paragraph definitions are not
 * handled — rare in practice; they pass through unchanged.
 *
 * Fenced code blocks and inline code spans are skipped for the inline
 * reference rewrite (same alternation-regex posture as
 * [rewriteHighlights]); definition extraction is line-based and also
 * skips fences.
 */
internal fun rewriteFootnotes(markdown: String): String {
    if (markdown.isEmpty() || "[^" !in markdown) return markdown
    val keepTrailingNewline = markdown.endsWith('\n')
    val sourceLines = markdown.lines()

    // Pass 1: extract definitions (id → text) in document order,
    // skipping fenced code. `dropped` marks the lines to remove from
    // the body. Kotlin forbids local data classes, so a Pair suffices.
    val defs = mutableListOf<Pair<String, String>>()
    val dropped = BooleanArray(sourceLines.size)
    var inFence = false
    var i = 0
    while (i < sourceLines.size) {
        val line = sourceLines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            i++
            continue
        }
        val match = if (!inFence) FOOTNOTE_DEF_REGEX.matchEntire(line) else null
        if (match != null) {
            val id = match.groupValues[1]
            val text = StringBuilder(match.groupValues[2].trim())
            dropped[i] = true
            // Fold one level of indented continuation lines (≥2 spaces
            // or a tab) into the definition text, stopping at the first
            // blank or non-indented line.
            var j = i + 1
            while (j < sourceLines.size &&
                sourceLines[j].isNotBlank() &&
                (sourceLines[j].startsWith("  ") || sourceLines[j].startsWith("\t"))
            ) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(sourceLines[j].trim())
                dropped[j] = true
                j++
            }
            defs.add(id to text.toString())
            i = j
        } else {
            i++
        }
    }
    if (defs.isEmpty()) return markdown

    // id → 1-based display number, first definition wins on duplicates.
    val numberById = HashMap<String, Int>()
    defs.forEachIndexed { index, (id, _) -> numberById.putIfAbsent(id, index + 1) }

    // Rebuild the body without the definition lines.
    val body = buildString {
        sourceLines.forEachIndexed { index, line ->
            if (!dropped[index]) {
                append(line)
                if (index < sourceLines.lastIndex) append('\n')
            }
        }
    }.trimEnd('\n')

    // Pass 2: rewrite inline references that resolve to a definition,
    // leaving fences / inline code / orphan refs untouched.
    val withRefs = FOOTNOTE_REF_PATTERN.replace(body) { m ->
        when {
            m.groups["fence"] != null -> m.value
            m.groups["code"] != null -> m.value
            m.groups["ref"] != null -> {
                val n = numberById[m.groups["id"]!!.value]
                if (n != null) "<sup>$n</sup>" else m.value
            }
            else -> m.value
        }
    }

    val section = buildString {
        append("\n\n---\n\n**Footnotes**\n\n")
        defs.forEachIndexed { index, (_, text) ->
            append(index + 1).append(". ").append(text).append('\n')
        }
    }
    val result = withRefs + section
    return if (keepTrailingNewline) result else result.trimEnd('\n')
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

// A footnote definition: 0–3 leading spaces, `[^id]:`, then the text.
// `id` disallows `]` and whitespace so `[^ ]` / `[^a b]` don't match.
private val FOOTNOTE_DEF_REGEX = Regex("^ {0,3}\\[\\^([^\\]\\s]+)]:\\s?(.*)$")

// Inline footnote reference `[^id]`, with the same fence/inline-code-first
// alternation as HIGHLIGHT_PATTERN so refs inside code samples survive.
private val FOOTNOTE_REF_PATTERN = Regex(
    "(?s)" +
        "(?<fence>```.*?```|~~~.*?~~~)" +
        "|(?<code>`[^`\\n]+`)" +
        "|(?<ref>\\[\\^(?<id>[^\\]\\s]+)])",
)
