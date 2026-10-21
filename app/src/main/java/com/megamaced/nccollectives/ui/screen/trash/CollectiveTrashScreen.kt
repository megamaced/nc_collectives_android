package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.util.uiText

@Composable
internal fun CollectiveTrashScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: CollectiveTrashViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    CollectiveTrash(
        innerPadding = innerPadding,
        state = ui,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRestore = { viewModel.restore(it.id) },
        onPurge = { viewModel.purge(it.id) },
        onStatusShown = viewModel::dismissStatus,
    )
}

/** The collectives trash, showing [state]; [CollectiveTrashScreen] binds it to its view model. */
@Composable
internal fun CollectiveTrash(
    innerPadding: PaddingValues,
    state: RemoteListUiState<Collective>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (Collective) -> Unit,
    onPurge: (Collective) -> Unit,
    onStatusShown: () -> Unit,
) {
    TrashListScaffold<Collective>(
        innerPadding = innerPadding,
        title = stringResource(R.string.trash_collectives_title),
        emptyTitle = stringResource(R.string.trash_empty_title),
        emptyMessage = stringResource(R.string.trash_collectives_empty_message),
        state = state,
        keyOf = { it.id },
        purgeTitle = { uiText(R.string.trash_collective_purge_title, it.name) },
        purgeMessage = { uiText(R.string.trash_collective_purge_message) },
        onBack = onBack,
        onRefresh = onRefresh,
        onRestore = onRestore,
        onPurge = onPurge,
        onStatusShown = onStatusShown,
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
