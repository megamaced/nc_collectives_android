package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A list header: [title] on the left, and on the right a button naming the
 * current order that opens a menu of [options]. Sits inside the list rather
 * than in the top bar, which on the collective list and the page tree is
 * already full — the same place the web app puts its sort control, above
 * the page list.
 */
@Composable
fun <T> SortHeader(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    // Read before the label, so a screen reader hears
                    // "Sort order, Name" rather than a bare "Name".
                    contentDescription = "Sort order",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(label(selected))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            menuOpen = false
                            onSelect(option)
                        },
                        trailingIcon = {
                            if (option == selected) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected")
                            }
                        },
                    )
                }
            }
        }
    }
}
