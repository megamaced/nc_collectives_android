package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.PageTag

/**
 * Toggle tags on the current page. As of Batch 18k (OCS-5), an inline
 * "Create tag…" row at the bottom of the sheet creates new tags via the
 * Collectives OCS API and attaches them to the current page in one go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    available: List<PageTag>,
    selectedTagNames: Set<String>,
    isLoading: Boolean,
    onToggle: (PageTag, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagName by remember { mutableStateOf("") }

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
                    text = "No tags defined for this collective yet. Create one below.",
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("Create tag…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newTagName.isNotBlank()) {
                            onCreate(newTagName)
                            newTagName = ""
                        }
                    }),
                )
                IconButton(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            onCreate(newTagName)
                            newTagName = ""
                        }
                    },
                    enabled = newTagName.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create tag")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
