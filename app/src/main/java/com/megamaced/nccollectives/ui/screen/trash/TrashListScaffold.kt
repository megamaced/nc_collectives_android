package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ListStateSwitch
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect

/**
 * The chrome both trash screens wear: a back-only top bar, the four-arm list
 * state, an overlaid spinner while re-listing over rows already on screen,
 * the status snackbar, and the irreversible-delete confirmation.
 *
 * R-61: the two differed only in labels, default emoji and one subtitle
 * line; the [row] slot is what stays per-screen, and both fill it with
 * [TrashRow]. [purgeTitle] and [purgeMessage] are lambdas because both
 * confirmations name the row being destroyed — the point of confirming.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> TrashListScaffold(
    innerPadding: PaddingValues,
    title: String,
    emptyTitle: String,
    emptyMessage: String,
    state: RemoteListUiState<T>,
    keyOf: (T) -> Any,
    purgeTitle: (T) -> String,
    purgeMessage: (T) -> String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (T) -> Unit,
    onPurge: (T) -> Unit,
    onStatusShown: () -> Unit,
    row: @Composable (item: T, onRestore: () -> Unit, onPurge: () -> Unit) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingPurge by remember { mutableStateOf<T?>(null) }

    SnackbarStatusEffect(
        message = state.statusMessage,
        host = snackbarHostState,
        onConsumed = onStatusShown,
    )

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            ListStateSwitch(
                isLoading = state.isLoading,
                error = state.errorMessage,
                isEmpty = state.items.isEmpty(),
                onRetry = onRefresh,
                empty = { EmptyState(title = emptyTitle, message = emptyMessage) },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = keyOf) { item ->
                        row(item, { onRestore(item) }, { pendingPurge = item })
                        HorizontalDivider()
                    }
                }
            }
            if (state.isLoading && state.items.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }

    pendingPurge?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text(purgeTitle(item)) },
            text = { Text(purgeMessage(item)) },
            confirmButton = {
                TextButton(onClick = {
                    onPurge(item)
                    pendingPurge = null
                }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("Cancel") }
            },
        )
    }
}

/** A trash row: emoji, title, optional subtitle, restore, delete forever. */
@Composable
internal fun TrashRow(
    emoji: String,
    title: String,
    subtitle: String?,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore")
        }
        IconButton(onClick = onPurge) {
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = "Delete forever",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
