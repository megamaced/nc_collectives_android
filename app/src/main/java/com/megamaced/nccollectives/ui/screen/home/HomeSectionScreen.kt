package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.rememberNowMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSectionScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPage: (Long) -> Unit,
    viewModel: HomeSectionViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val collectives by viewModel.collectives.collectAsStateWithLifecycle()
    val recentFilter by viewModel.recentFilter.collectAsStateWithLifecycle()
    val nowMillis by rememberNowMillis()
    val section = viewModel.section
    val labels = remember(collectives) { collectives.associate { it.id to it.label } }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(section.title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { scaffoldPadding ->
        Column(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            if (section == HomeSection.RecentlyUpdated && showsCollectiveFilter(collectives)) {
                CollectiveFilterChips(
                    collectives = collectives,
                    selected = recentFilter,
                    onSelect = viewModel::setRecentFilter,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            when (val loaded = content) {
                null -> {
                    Box(Modifier.fillMaxSize())
                }

                is HomeSectionContent.Attention -> {
                    if (loaded.rows.isEmpty()) {
                        EmptyState(title = "All caught up", message = "No conflicts to resolve and no edits waiting to sync.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(loaded.rows, key = { it.page.id }) { row ->
                                HomePageRow(
                                    page = row.page,
                                    caption = labels[row.page.collectiveId],
                                    supporting = row.supporting,
                                    onClick = { onOpenPage(row.page.id) },
                                )
                            }
                        }
                    }
                }

                is HomeSectionContent.Pages -> {
                    if (loaded.pages.isEmpty()) {
                        EmptyState(title = "Nothing here", message = "No pages to show here right now.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            pageRows(section, loaded.pages, labels, nowMillis, onOpenPage)
                            if (loaded.hasMore) {
                                item(key = "load-more") {
                                    TextButton(
                                        onClick = viewModel::loadMore,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    ) { Text("Load more") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
