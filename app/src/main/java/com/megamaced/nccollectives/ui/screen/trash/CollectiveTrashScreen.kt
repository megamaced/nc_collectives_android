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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectiveTrashScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: CollectiveTrashViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingPurge by remember { mutableStateOf<Collective?>(null) }

    LaunchedEffect(ui.statusMessage) {
        ui.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatus()
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Collective trash", style = MaterialTheme.typography.titleMedium) },
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
            when {
                ui.isLoading && ui.items.isEmpty() -> LoadingState()
                ui.errorMessage != null && ui.items.isEmpty() ->
                    ErrorState(message = ui.errorMessage!!, onRetry = viewModel::refresh)
                ui.items.isEmpty() ->
                    EmptyState(
                        title = "Trash is empty",
                        message = "Collectives you move to trash will appear here.",
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(ui.items, key = { it.id }) { collective ->
                            TrashRow(
                                collective = collective,
                                onRestore = { viewModel.restore(collective.id) },
                                onPurge = { pendingPurge = collective },
                            )
                            HorizontalDivider()
                        }
                    }
            }
            if (ui.isLoading && ui.items.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }

    pendingPurge?.let { collective ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("Permanently delete \"${collective.name}\"?") },
            text = {
                Text(
                    "This will permanently delete the collective and tear down the underlying Team. " +
                        "All pages will be lost forever. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(collective.id)
                    pendingPurge = null
                }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrashRow(
    collective: Collective,
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
        Text(
            text = collective.emoji?.takeIf { it.isNotBlank() } ?: "📓",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = collective.name, style = MaterialTheme.typography.bodyLarge)
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
