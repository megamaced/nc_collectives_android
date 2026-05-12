package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HomeScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()
    val recent by viewModel.recent.collectAsState()

    if (favorites.isEmpty() && recent.isEmpty()) {
        EmptyState(
            title = "Nothing yet",
            message = "Favorite a page or open a collective to see it here.",
            modifier = Modifier.padding(innerPadding),
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        if (favorites.isNotEmpty()) {
            SectionHeader("Favorites")
            FavoriteList(favorites = favorites, onOpenPage = onOpenPage)
        }
        if (recent.isNotEmpty()) {
            SectionHeader("Recent")
            RecentList(recent = recent, onOpenPage = onOpenPage)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun FavoriteList(
    favorites: List<Page>,
    onOpenPage: (Long) -> Unit,
) {
    // Use a non-lazy Column inside the scrolling parent — favorites are
    // capped by how many the user can realistically favorite (~ tens).
    favorites.forEach { page ->
        PageRow(page = page, onClick = { onOpenPage(page.id) }, showTimestamp = false)
        HorizontalDivider()
    }
}

@Composable
private fun RecentList(
    recent: List<Page>,
    onOpenPage: (Long) -> Unit,
) {
    // Capped at 10 in the ViewModel; a plain forEach is fine here.
    recent.forEach { page ->
        PageRow(page = page, onClick = { onOpenPage(page.id) }, showTimestamp = true)
        HorizontalDivider()
    }
}

@Composable
private fun PageRow(
    page: Page,
    onClick: () -> Unit,
    showTimestamp: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(text = page.title, style = MaterialTheme.typography.bodyLarge)
            val subtitle = buildSubtitle(page, showTimestamp)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildSubtitle(
    page: Page,
    showTimestamp: Boolean,
): String? {
    val edited = if (showTimestamp && page.serverTimestamp > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(page.serverTimestamp * 1000L))
    } else {
        null
    }
    val parts = listOfNotNull(
        page.lastUserDisplayName.takeIf { it.isNotEmpty() && showTimestamp }?.let { "by $it" },
        edited,
    )
    return parts.joinToString(" • ").takeIf { it.isNotEmpty() }
}
