package com.megamaced.nccollectives.ui.screen.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.NotifyLevel

/**
 * How much each of [collectives] notifies the user, as the web app's
 * per-collective menu sets it. Only collectives whose level the server
 * reports are offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationSettingsSheet(
    collectives: List<Collective>,
    onSetLevel: (collectiveId: Long, level: NotifyLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Mentions notify you only when someone mentions you; all changes notify you whenever a page changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(collectives, key = { it.id }) { collective ->
                    NotifyLevelChooser(
                        title = collective.label,
                        level = collective.notifyLevel,
                        onSelect = { onSetLevel(collective.id, it) },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

/** One collective's level as three segments: Off, Mentions, All changes. */
@Composable
internal fun NotifyLevelChooser(
    title: String,
    level: NotifyLevel?,
    onSelect: (NotifyLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            NotifyLevel.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == level,
                    onClick = { if (option != level) onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, NotifyLevel.entries.size),
                ) { Text(option.label()) }
            }
        }
    }
}

internal fun NotifyLevel.label(): String =
    when (this) {
        NotifyLevel.Off -> "Off"
        NotifyLevel.Mentions -> "Mentions"
        NotifyLevel.All -> "All changes"
    }
