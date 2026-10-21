package com.megamaced.nccollectives.ui.screen.home

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.screen.collective.NewPageDialog
import com.megamaced.nccollectives.ui.screen.collective.NewPageMode

/**
 * The collective's New page dialog with one more question first: which
 * collective. [collectives] are the ones the user can write to.
 *
 * [parents] are offered only once they are [collectiveId]'s: until then the
 * dialog has no parent to offer and Create stays off, rather than pairing
 * the new collective with the last one's pages. The same goes for a chosen
 * collective that is no longer one the user can write to. [busy] holds
 * Create back while an earlier page is still being created.
 */
@Composable
internal fun HomeNewPageDialog(
    collectives: List<Collective>,
    collectiveId: Long?,
    parents: NewPageParents?,
    onSelectCollective: (Long) -> Unit,
    onCreate: (collectiveId: Long, parentId: Long, title: String) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    var showCollectiveSheet by remember { mutableStateOf(false) }
    val selected = collectives.firstOrNull { it.id == collectiveId }
    val parentChoices = parents?.takeIf { selected != null && it.collectiveId == selected.id }?.choices.orEmpty()

    NewPageDialog(
        mode = NewPageMode.PickParent,
        parentChoices = parentChoices,
        onCreate = { parentId, title -> if (selected != null) onCreate(selected.id, parentId, title) },
        onDismiss = onDismiss,
        parentKey = collectiveId,
        showParent = selected != null,
        busy = busy,
        header = {
            Text(stringResource(R.string.home_new_page_which_collective), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { showCollectiveSheet = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.label ?: stringResource(R.string.home_new_page_pick_collective_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
    )

    if (showCollectiveSheet) {
        CollectivePickerSheet(
            collectives = collectives,
            onPick = {
                onSelectCollective(it.id)
                showCollectiveSheet = false
            },
            onDismiss = { showCollectiveSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectivePickerSheet(
    collectives: List<Collective>,
    onPick: (Collective) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_new_page_pick_collective), style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(collectives, key = { it.id }) { collective ->
                    Text(
                        text = collective.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(collective) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
