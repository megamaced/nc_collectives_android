package com.megamaced.nccollectives.ui.screen.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.navigation.OnReselected
import com.megamaced.nccollectives.ui.navigation.scrollToTop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    OnReselected(reselected) { listState.scrollToTop() }

    SnackbarMessageEffect(viewModel.messages, snackbarHostState)
    val resources by rememberUpdatedState(LocalResources.current)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.removed.collect { page ->
            // The latest removal is the one offered. It replaces an offer
            // still showing, whose Undo lapses as it would have on timing
            // out, rather than queueing behind it; launched so this keeps
            // listening while it shows.
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.favorites_removed, page.title),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.restore(page)
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            FavoritesContent(
                groups = groups,
                onOpenPage = onOpenPage,
                onUnfavorite = viewModel::unfavorite,
                listState = listState,
            )
        }
    }
}

/** Favorites grouped under their collectives, or a note that there are none yet. */
@Composable
internal fun FavoritesContent(
    groups: List<FavoriteGroup>,
    onOpenPage: (Long) -> Unit,
    onUnfavorite: (PageListItem) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    if (groups.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.favorites_empty_title),
            message = stringResource(R.string.favorites_empty_message),
        )
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "collective-${group.collectiveId}", contentType = "header") {
                Text(
                    text = group.collectiveLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            // A page id is unique within its collective, which is all the
            // key needs to be within its group.
            items(group.pages, key = { "page-${group.collectiveId}-${it.id}" }, contentType = { "page" }) { page ->
                FavoriteRow(
                    page = page,
                    onClick = { onOpenPage(page.id) },
                    onUnfavorite = { onUnfavorite(page) },
                )
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    page: PageListItem,
    onClick: () -> Unit,
    onUnfavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = page.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onUnfavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.favorites_remove, page.title),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
