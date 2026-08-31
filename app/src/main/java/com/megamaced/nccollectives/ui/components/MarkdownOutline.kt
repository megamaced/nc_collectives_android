package com.megamaced.nccollectives.ui.components

import android.text.Spanned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.noties.markwon.core.spans.HeadingSpan

/**
 * One entry in a page's index (issue #15).
 *
 * [charOffset] is an offset into the *rendered* `Spanned`, not into the
 * markdown source — the two diverge as soon as any of the dialect rewrites
 * in `MarkdownView` fire — which is what makes it usable directly against
 * the `TextView`'s `Layout`.
 */
data class PageHeading(
    val level: Int,
    val title: String,
    val charOffset: Int,
)

/**
 * Bridge between a [MarkdownView] and a caller that wants to jump around
 * inside it.
 *
 * The headings are read back off the rendered `Spanned` rather than parsed
 * out of the markdown, so the index always agrees with what is actually on
 * screen: no entry for a `#` inside a fenced code block, no missed setext
 * heading, and nothing to keep in sync with the dialect rewrites the view
 * applies before rendering.
 */
@Stable
class MarkdownOutline {
    var headings: List<PageHeading> by mutableStateOf(emptyList())
        private set

    /**
     * Resolves a rendered character offset to its y position, in px, from
     * the top of the body view. Null until the view has been laid out —
     * `TextView.getLayout()` is null before measure — which is why this is
     * a lambda evaluated on demand rather than a table computed up front.
     */
    private var lineTopResolver: ((Int) -> Int?)? = null

    internal fun onRendered(
        headings: List<PageHeading>,
        lineTopResolver: (Int) -> Int?,
    ) {
        // Guarded: this runs from `AndroidView`'s update block, and an
        // unconditional write would schedule a recomposition on every pass.
        if (this.headings != headings) {
            this.headings = headings
        }
        this.lineTopResolver = lineTopResolver
    }

    /** Y offset of [heading] from the top of the rendered body, in px. */
    fun topOf(heading: PageHeading): Int? = lineTopResolver?.invoke(heading.charOffset)
}

@Composable
fun rememberMarkdownOutline(): MarkdownOutline = remember { MarkdownOutline() }

/**
 * A heading as read straight off the rendered `Spanned`, before any
 * tidying. Exists so the tidying — which is where the decisions are — can
 * be a pure function the JVM test suite can reach; `Spanned` itself is
 * Android framework and would need Robolectric or an instrumented test.
 */
internal data class RawHeading(
    val level: Int,
    val start: Int,
    val end: Int,
    val text: CharSequence,
)

/**
 * Pull the heading structure out of a rendered markdown `Spanned`.
 *
 * Markwon tags every heading — ATX, setext, and the `<h1>`…`<h6>` that
 * `HtmlPlugin` handles — with a [HeadingSpan], which carries the level.
 */
internal fun headingsFrom(text: CharSequence): List<PageHeading> {
    if (text !is Spanned) return emptyList()
    return outlineOf(
        text.getSpans(0, text.length, HeadingSpan::class.java).map { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            RawHeading(
                level = span.level,
                start = start,
                end = end,
                text = if (start >= 0 && end in start..text.length) {
                    text.subSequence(start, end)
                } else {
                    ""
                },
            )
        },
    )
}

/**
 * Order, clean and filter raw heading spans into an index.
 *
 * `getSpans` makes no ordering promise, so the list is sorted into document
 * order here. A heading that renders to nothing (`##` alone on a line) is
 * dropped rather than listed as a blank row, as is one whose span the
 * renderer left detached (`getSpanStart` returns -1).
 */
internal fun outlineOf(raw: List<RawHeading>): List<PageHeading> =
    raw
        .asSequence()
        .filter { it.start >= 0 && it.end > it.start }
        .sortedBy { it.start }
        .mapNotNull { heading ->
            val title = heading.text.toString().trim()
            if (title.isEmpty()) {
                null
            } else {
                PageHeading(
                    level = heading.level.coerceIn(1, 6),
                    title = title,
                    charOffset = heading.start,
                )
            }
        }.toList()
