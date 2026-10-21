package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem

/**
 * Collapsible "Linked from" section for [PageViewScreen]. Hidden entirely
 * when [pages] is empty. The header stays visible when collapsed so the
 * count is always discoverable.
 */
@Composable
fun BacklinkChipRow(
    pages: List<Page>,
    onOpenPage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = remember(pages) { pages.map { PageChip(it.id, it.emoji, it.title) } }
    PageChipSection(label = stringResource(R.string.page_linked_from), chips = chips, onOpenPage = onOpenPage, modifier = modifier)
}

/**
 * Collapsible "Subpages" section for [PageViewScreen], so a page's children
 * are one tap away wherever the page was opened from — Favorites, search,
 * a recent-pages card or a link — and not only from the collective's tree.
 * [pages] arrive already in the collective's page order.
 */
@Composable
fun SubpageChipRow(
    pages: List<PageListItem>,
    onOpenPage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = remember(pages) { pages.map { PageChip(it.id, it.emoji, it.title) } }
    PageChipSection(label = stringResource(R.string.page_subpages), chips = chips, onOpenPage = onOpenPage, modifier = modifier)
}

/** What a chip draws: both page models reduce to this. */
private data class PageChip(
    val id: Long,
    val emoji: String?,
    val title: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageChipSection(
    label: String,
    chips: List<PageChip>,
    onOpenPage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    // Saveable so a section the user opened survives rotation and the trip
    // into a linked page and back.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.page_chip_section, label, chips.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip ->
                    AssistChip(
                        onClick = { onOpenPage(chip.id) },
                        label = {
                            val emoji = chip.emoji?.takeIf { it.isNotBlank() }
                            Text(text = if (emoji != null) "$emoji ${chip.title}" else chip.title)
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}
