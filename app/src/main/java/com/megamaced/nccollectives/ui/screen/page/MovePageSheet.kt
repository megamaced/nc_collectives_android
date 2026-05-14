package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.canHoldChildren

/**
 * Pick a new parent for the current page. Only folder pages (`fileName ==
 * Readme.md`) and the collective's landing page (parentId == 0) are valid
 * targets; leaf pages can't have children.
 *
 * Cross-collective moves aren't supported in Batch 11; targets are filtered
 * to the page's own collective by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePageSheet(
    targets: List<Page>,
    onPick: (Page) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val folderTargets = targets.filter { it.canHoldChildren() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Move page to…", style = MaterialTheme.typography.titleMedium)
            if (folderTargets.isEmpty()) {
                Text(
                    text = "No folder pages available in this collective.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(folderTargets, key = { it.id }) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(page) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📁",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(text = page.title, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
