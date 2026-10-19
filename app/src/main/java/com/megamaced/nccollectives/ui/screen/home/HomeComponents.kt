package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageAttention
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.TagCount

/** A section title, with "See all" when [onSeeAll] is given. */
@Composable
internal fun HomeSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    seeAllLabel: String = "See all",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .semantics { heading() },
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) { Text(seeAllLabel) }
        }
    }
}

/**
 * One page in a Home list: its emoji and title over [caption] (which
 * collective) and [supporting] (what happened to it), whichever are known.
 */
@Composable
internal fun HomePageRow(
    page: PageListItem,
    caption: String?,
    supporting: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(caption, supporting).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The unseen alerts, as a row at the top of "Needs attention" that leads to the Alerts tab. */
@Composable
internal fun UnseenAlertsRow(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "🔔", style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (count == 1) "1 new alert" else "$count new alerts",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Mentions and changes in your collectives",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "All" plus one chip per collective; [selected] null is All. */
@Composable
internal fun CollectiveFilterChips(
    collectives: List<Collective>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(collectives, key = { it.id }) { collective ->
            FilterChip(
                selected = selected == collective.id,
                onClick = { onSelect(collective.id) },
                label = { Text(collective.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Tag chips with each tag's page count, most used first. */
@Composable
internal fun TagChips(
    tags: List<TagCount>,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { onTag(tag.name) },
                label = { Text("${tag.name} · ${tag.pageCount}") },
            )
        }
    }
}

/**
 * A page in "Needs attention" and what it is waiting on. A page can be
 * waiting on both at once, a conflict draft and a newer queued edit, and is
 * then one row saying so.
 */
internal data class AttentionRow(
    val page: PageListItem,
    val hasConflict: Boolean,
    val hasQueuedEdit: Boolean,
) {
    val supporting: String
        get() = when {
            hasConflict && hasQueuedEdit -> "Unsaved changes on this device · an edit waiting to sync"
            hasConflict -> "Unsaved changes on this device"
            else -> "Saved offline — waiting to sync"
        }
}

/** Conflicts first, since only the user can resolve them; then queued edits, oldest first. */
internal fun PageAttention.rows(): List<AttentionRow> {
    val conflictIds = conflicts.mapTo(HashSet()) { it.id }
    val queuedIds = queued.mapTo(HashSet()) { it.id }
    return conflicts.map { AttentionRow(it, hasConflict = true, hasQueuedEdit = it.id in queuedIds) } +
        queued.filterNot { it.id in conflictIds }.map { AttentionRow(it, hasConflict = false, hasQueuedEdit = true) }
}

/**
 * A section's pages as a grid of cards: as many columns as fit at
 * [MIN_CARD_WIDTH] or wider, and never fewer than two, filled a row at a
 * time. Each card shows the page's emoji and title, then [caption] — which
 * collective it is in — and [supporting], who edited it and when. The cards
 * in a row share the tallest one's height, so a larger font grows the row
 * rather than clipping a card. Draws nothing when [pages] is empty.
 *
 * Not lazy: Home shows a handful of cards per section, inside a list that
 * is itself lazy.
 *
 * R-54: takes [PageListItem]s. A card shows an emoji, a title and a line
 * or two about the page — nothing that needs a body — so the grid is one of
 * the list consumers that must be incapable of reading one.
 */
@Composable
internal fun PageCardGrid(
    pages: List<PageListItem>,
    onPageClick: (Long) -> Unit,
    caption: (PageListItem) -> String?,
    supporting: (PageListItem) -> String?,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val columns = cardColumns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            pages.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    row.forEach { page ->
                        PageCard(
                            page = page,
                            caption = caption(page),
                            supporting = supporting(page),
                            onClick = { onPageClick(page.id) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // A short last row keeps its cards the width of the rest.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How many card columns fit in [width]: at least two. */
internal fun cardColumns(width: Dp): Int = maxOf(2, ((width + CARD_GAP) / (MIN_CARD_WIDTH + CARD_GAP)).toInt())

/** The narrowest a card in [PageCardGrid] gets before the grid drops a column. */
internal val MIN_CARD_WIDTH = 160.dp

private val CARD_GAP = 8.dp

@Composable
private fun PageCard(
    page: PageListItem,
    caption: String?,
    supporting: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
                style = MaterialTheme.typography.headlineSmall,
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(caption, supporting).filter { it.isNotBlank() }.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
