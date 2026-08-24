package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.Page

@Composable
internal fun TrashScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    TrashListScaffold<Page>(
        innerPadding = innerPadding,
        title = "Trash",
        emptyTitle = "Trash is empty",
        emptyMessage = "Pages you delete from this collective will appear here.",
        state = ui,
        keyOf = { it.id },
        purgeTitle = { "Permanently delete?" },
        purgeMessage = { "\"${it.title}\" will be deleted forever. This can't be undone." },
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRestore = { viewModel.restore(it.id) },
        onPurge = { viewModel.purge(it.id) },
        onStatusShown = viewModel::dismissStatus,
    ) { page, onRestore, onPurge ->
        TrashRow(
            emoji = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            title = page.title,
            subtitle = page.lastUserDisplayName.takeIf { it.isNotEmpty() }?.let { "By $it" },
            onRestore = onRestore,
            onPurge = onPurge,
        )
    }
}
