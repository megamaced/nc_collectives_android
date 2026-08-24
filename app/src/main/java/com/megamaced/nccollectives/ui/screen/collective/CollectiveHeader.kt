package com.megamaced.nccollectives.ui.screen.collective

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem

/**
 * Horizontal "Recent pages" strip rendered above the tree on
 * [PageTreeScreen]. Mirrors the widget Nextcloud's web client shows on the
 * collective landing page. Hidden when [pages] is empty.
 *
 * R-54: takes [PageListItem]s. A card shows an emoji, a title and a
 * relative timestamp — nothing that needs a body — so the strip is one of
 * the list consumers that must be incapable of reading one.
 */
@Composable
internal fun RecentPagesStrip(
    pages: List<PageListItem>,
    onPageClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Recent pages",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pages, key = { it.id }) { page ->
                RecentPageCard(page = page, onClick = { onPageClick(page.id) })
            }
        }
    }
}

@Composable
private fun RecentPageCard(
    page: PageListItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(132.dp)
            .height(132.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
                style = MaterialTheme.typography.headlineSmall,
            )
            Column {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = relativeTime(page.serverTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Material card showing the collective's landing page (emoji + name + first
 * few lines of the body). Rendered above the tree on phone-sized screens
 * only — tablet/foldable layouts will get a proper two-pane view in a
 * future batch. Tap routes into the landing page.
 *
 * R-56: the one card on this screen that keeps a whole [Page]. It is the
 * only list-screen consumer that legitimately renders markdown, so it is
 * fed by a dedicated single-row flow (`observeLandingPage`) — widening the
 * tree's list projection to carry bodies for its sake would have handed a
 * body to all 200 rows to draw two lines on one of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LandingPageCard(
    landing: Page,
    collectiveName: String,
    collectiveEmoji: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // R-45: [plainTextPreview] runs a chain of regexes over the landing
    // body to fill a two-line snippet. Key it on the body so it runs once
    // per fetched body rather than on every recomposition — `updateBody`
    // from `primeLandingBody` recomposes this card, as does every
    // unrelated `PageTreeUiState` write.
    val snippet = remember(landing.bodyMd) {
        landing.bodyMd?.let { plainTextPreview(it) }.orEmpty()
    }
    val displayName = collectiveName.ifBlank { landing.title }
    val displayEmoji = collectiveEmoji?.takeIf { it.isNotBlank() }
        ?: landing.emoji?.takeIf { it.isNotBlank() }
        ?: "🏠"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = displayEmoji, style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome to $displayName",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (snippet.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Render a server-timestamp (seconds since epoch) as a relative-time label
 * like "5 minutes ago". Falls back to an absolute date if the value can't
 * be turned into a sensible label.
 */
private fun relativeTime(serverTimestampSeconds: Long): String {
    if (serverTimestampSeconds <= 0L) return ""
    val millis = serverTimestampSeconds * 1_000L
    val span = DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    return span.toString()
}

// R-45: hoisted out of [plainTextPreview] so the seven patterns compile
// once at class-init instead of once per call — the preview sits on the
// landing card's recomposition path.
private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`[^`]*`")
private val IMAGE_REF = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val TEXT_LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
private val LINE_LEAD_MARKERS = Regex("(?m)^[#>*\\-+\\s]{1,6}")
private val EMPHASIS_MARKERS = Regex("[*_~]{1,3}")
private val WHITESPACE_RUN = Regex("\\s+")

private const val CODE_FENCE = "```"

/**
 * Longest prefix of the body [plainTextPreview] scans (R-45). The snippet
 * feeds a `maxLines = 2` `Text`, roughly 80 characters — scanning a
 * multi-kilobyte wiki page to fill it is pure waste.
 */
private const val PREVIEW_SCAN_LIMIT = 1500

/**
 * Strip common markdown markers for a short preview snippet — not a full
 * renderer, just enough to avoid showing `## Heading`, `**bold**`, or
 * `[text](url)` literal syntax in the card. Collapses whitespace.
 *
 * `internal` for `LandingPreviewTest`, which pins the output against the
 * R-45 rewrite.
 */
internal fun plainTextPreview(markdown: String): String {
    val capped = capForPreview(markdown)
    val preview = stripMarkdown(capped)
    // The cap can swallow the lot when a body opens with a code block
    // longer than [PREVIEW_SCAN_LIMIT] — the text after it is what the
    // uncapped scan would have shown. Rare enough to pay for a full scan.
    return if (preview.isEmpty() && capped.length < markdown.length) {
        stripMarkdown(markdown)
    } else {
        preview
    }
}

private fun stripMarkdown(markdown: String): String {
    var s = markdown
    // Drop fenced code blocks entirely.
    s = s.replace(FENCED_CODE, " ")
    // Drop inline code.
    s = s.replace(INLINE_CODE, " ")
    // Replace [text](url) with text.
    s = s.replace(IMAGE_REF, " ")
    s = s.replace(TEXT_LINK, "$1")
    // Strip leading heading hashes / bullets / blockquote markers.
    s = s.replace(LINE_LEAD_MARKERS, "")
    // Bold / italic / strike markers.
    s = s.replace(EMPHASIS_MARKERS, "")
    // Collapse whitespace.
    s = s.replace(WHITESPACE_RUN, " ").trim()
    return s
}

/**
 * Trim the body to [PREVIEW_SCAN_LIMIT] without leaving a half-written
 * markdown construct behind for [stripMarkdown] to miss.
 *
 * Two guards: cut back to the last line break (a truncated `[text](url`
 * or `**bold` would otherwise survive as literal syntax), and drop a
 * dangling code fence — an odd fence count means the cap landed inside a
 * code block whose opener no longer has a closer for [FENCED_CODE] to
 * pair with, so the code itself would leak into the snippet.
 */
private fun capForPreview(markdown: String): String {
    if (markdown.length <= PREVIEW_SCAN_LIMIT) return markdown
    val head = markdown.take(PREVIEW_SCAN_LIMIT)
    val lastBreak = head.lastIndexOf('\n')
    val whole = if (lastBreak > 0) head.substring(0, lastBreak) else head
    val fences = whole.split(CODE_FENCE).size - 1
    return if (fences % 2 == 1) whole.substringBeforeLast(CODE_FENCE) else whole
}
