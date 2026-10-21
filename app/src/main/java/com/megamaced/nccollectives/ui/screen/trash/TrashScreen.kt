package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.util.uiText

@Composable
internal fun TrashScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    PageTrash(
        innerPadding = innerPadding,
        state = ui,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRestore = { viewModel.restore(it.id) },
        onPurge = { viewModel.purge(it.id) },
        onStatusShown = viewModel::dismissStatus,
    )
}

/** A collective's page trash, showing [state]; [TrashScreen] binds it to its view model. */
@Composable
internal fun PageTrash(
    innerPadding: PaddingValues,
    state: RemoteListUiState<Page>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (Page) -> Unit,
    onPurge: (Page) -> Unit,
    onStatusShown: () -> Unit,
) {
    TrashListScaffold<Page>(
        innerPadding = innerPadding,
        title = stringResource(R.string.trash_title),
        emptyTitle = stringResource(R.string.trash_empty_title),
        emptyMessage = stringResource(R.string.trash_pages_empty_message),
        state = state,
        keyOf = { it.id },
        purgeTitle = { uiText(R.string.trash_page_purge_title) },
        purgeMessage = { uiText(R.string.trash_page_purge_message, it.title) },
        onBack = onBack,
        onRefresh = onRefresh,
        onRestore = onRestore,
        onPurge = onPurge,
        onStatusShown = onStatusShown,
    ) { page, onRestore, onPurge ->
        TrashRow(
            emoji = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            title = page.title,
            subtitle = page.lastUserDisplayName.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.trash_page_by, it) },
            onRestore = onRestore,
            onPurge = onPurge,
        )
    }
}
