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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Page

/**
 * Horizontal "Recent pages" strip rendered above the tree on
 * [PageTreeScreen]. Mirrors the widget Nextcloud's web client shows on the
 * collective landing page. Hidden when [pages] is empty.
 */
@Composable
internal fun RecentPagesStrip(
    pages: List<Page>,
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
    page: Page,
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
    val snippet = landing.bodyMd?.let { plainTextPreview(it) }.orEmpty()
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

/**
 * Strip common markdown markers for a short preview snippet — not a full
 * renderer, just enough to avoid showing `## Heading`, `**bold**`, or
 * `[text](url)` literal syntax in the card. Collapses whitespace.
 */
private fun plainTextPreview(markdown: String): String {
    var s = markdown
    // Drop fenced code blocks entirely.
    s = s.replace(Regex("```[\\s\\S]*?```"), " ")
    // Drop inline code.
    s = s.replace(Regex("`[^`]*`"), " ")
    // Replace [text](url) with text.
    s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), " ")
    s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    // Strip leading heading hashes / bullets / blockquote markers.
    s = s.replace(Regex("(?m)^[#>*\\-+\\s]{1,6}"), "")
    // Bold / italic / strike markers.
    s = s.replace(Regex("[*_~]{1,3}"), "")
    // Collapse whitespace.
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
}
