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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Page

/**
 * Collapsible "Linked from" section for [PageViewScreen]. Hidden entirely
 * when [pages] is empty. The header stays visible when collapsed so the
 * count is always discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklinkChipRow(
    pages: List<Page>,
    onOpenPage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

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
                text = "Linked from (${pages.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEach { page ->
                    AssistChip(
                        onClick = { onOpenPage(page.id) },
                        label = {
                            val emoji = page.emoji?.takeIf { it.isNotBlank() }
                            Text(text = if (emoji != null) "$emoji ${page.title}" else page.title)
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}
