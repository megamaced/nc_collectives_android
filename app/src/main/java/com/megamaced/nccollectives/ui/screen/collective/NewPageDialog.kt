package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.PageListItem

internal sealed interface NewPageMode {
    /** A New page button — let the user pick a parent. */
    object PickParent : NewPageMode

    /** Per-row "+" action — parent is fixed. */
    data class FixedParent(
        val parentId: Long,
    ) : NewPageMode
}

/**
 * The parent a new page goes under until the user picks one: the
 * collective's landing page, so the page lands at the top level, or failing
 * that the first choice.
 */
internal fun defaultParentId(choices: List<PageListItem>): Long? =
    choices.firstOrNull { it.parentId == 0L }?.id ?: choices.firstOrNull()?.id

/**
 * Title and, in [NewPageMode.PickParent], parent for a new page.
 *
 * Until the user picks a parent, the one shown is [defaultParentId], worked
 * out on every composition rather than captured when the dialog opens, so
 * choices that arrive after it opens still get a default. Once they have
 * picked, it is their pick or nothing: if the pick leaves [parentChoices],
 * Create waits for them to choose again rather than quietly moving the page
 * somewhere they didn't say. [parentKey] says which set of choices a pick
 * belongs to: when it changes, the pick is dropped.
 *
 * [header] goes above the parent row, for a caller that also needs the user
 * to say which collective; [showParent] false holds the parent row back
 * until they have. [busy] holds Create back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewPageDialog(
    mode: NewPageMode,
    parentChoices: List<PageListItem>,
    onCreate: (parentId: Long, title: String) -> Unit,
    onDismiss: () -> Unit,
    parentKey: Any? = null,
    header: (@Composable () -> Unit)? = null,
    showParent: Boolean = true,
    busy: Boolean = false,
) {
    var title by remember { mutableStateOf("") }
    var pickedParentId by remember(mode, parentKey) { mutableStateOf<Long?>(null) }
    var showParentSheet by remember { mutableStateOf(false) }

    val parentId = when (mode) {
        is NewPageMode.FixedParent -> {
            mode.parentId
        }

        NewPageMode.PickParent -> {
            val picked = pickedParentId
            if (picked == null) defaultParentId(parentChoices) else picked.takeIf { parentChoices.any { it.id == picked } }
        }
    }
    val parent = parentChoices.firstOrNull { it.id == parentId }
    val canCreate = title.isNotBlank() && parentId != null && showParent && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header?.invoke()
                if (mode is NewPageMode.PickParent && showParent) {
                    Text(stringResource(R.string.page_new_where), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showParentSheet = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = parent?.emoji?.takeIf { it.isNotBlank() } ?: "📁",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = parent?.title ?: stringResource(R.string.page_new_pick_parent_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (parent != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.page_title_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canCreate) onCreate(parentId!!, title.trim())
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = { onCreate(parentId!!, title.trim()) },
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showParentSheet) {
        ParentPickerSheet(
            choices = parentChoices,
            onPick = {
                pickedParentId = it.id
                showParentSheet = false
            },
            onDismiss = { showParentSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentPickerSheet(
    choices: List<PageListItem>,
    onPick: (PageListItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.page_new_pick_parent), style = MaterialTheme.typography.titleMedium)
            if (choices.isEmpty()) {
                Text(
                    text = stringResource(R.string.page_new_no_parents),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it.id }) { page ->
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
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
