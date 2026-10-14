package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
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
