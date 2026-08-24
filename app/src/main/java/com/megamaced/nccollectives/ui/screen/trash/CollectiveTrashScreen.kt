package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.Collective

@Composable
internal fun CollectiveTrashScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: CollectiveTrashViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    TrashListScaffold<Collective>(
        innerPadding = innerPadding,
        title = "Collective trash",
        emptyTitle = "Trash is empty",
        emptyMessage = "Collectives you move to trash will appear here.",
        state = ui,
        keyOf = { it.id },
        purgeTitle = { "Permanently delete \"${it.name}\"?" },
        purgeMessage = {
            "This will permanently delete the collective and tear down the underlying Team. " +
                "All pages will be lost forever. This can't be undone."
        },
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRestore = { viewModel.restore(it.id) },
        onPurge = { viewModel.purge(it.id) },
        onStatusShown = viewModel::dismissStatus,
    ) { collective, onRestore, onPurge ->
        TrashRow(
            emoji = collective.emoji?.takeIf { it.isNotBlank() } ?: "📓",
            title = collective.name,
            subtitle = null,
            onRestore = onRestore,
            onPurge = onPurge,
        )
    }
}
