package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.PageTag

/**
 * Toggle tags on the current page. Only existing tags are shown — creating a
 * new tag requires the Nextcloud system-tags API, which Collectives doesn't
 * proxy. Direct users to create tags in the web UI for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    available: List<PageTag>,
    selectedTagNames: Set<String>,
    isLoading: Boolean,
    onToggle: (PageTag, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tags", style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> CircularProgressIndicator()
                available.isEmpty() -> Text(
                    text = "No tags defined for this collective. Create one in the web UI first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { tag ->
                        val selected = tag.name in selectedTagNames
                        FilterChip(
                            selected = selected,
                            onClick = { onToggle(tag, !selected) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
