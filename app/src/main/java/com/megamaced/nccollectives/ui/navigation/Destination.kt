package com.megamaced.nccollectives.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

internal sealed class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    object Collectives : Destination("collectives", "Collectives", Icons.Outlined.MenuBook)

    object Search : Destination("search", "Search", Icons.Outlined.Search)

    object Favorites : Destination("favorites", "Favorites", Icons.Outlined.Bookmark)

    object Settings : Destination("settings", "Settings", Icons.Outlined.Settings)

    /** Nested destination — not in the bottom bar. */
    object PageTree : Destination("pageTree/{collectiveId}", "Pages", Icons.Outlined.MenuBook) {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun route(collectiveId: Long) = "pageTree/$collectiveId"
    }

    /** Nested destination — not in the bottom bar. */
    object PageView : Destination("page/{pageId}", "Page", Icons.Outlined.MenuBook) {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId"
    }

    /** Nested destination — not in the bottom bar. */
    object PageEdit : Destination("page/{pageId}/edit", "Edit", Icons.Outlined.MenuBook) {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/edit"
    }

    /** Nested destination — not in the bottom bar. */
    object Attachments : Destination("page/{pageId}/attachments", "Attachments", Icons.Outlined.MenuBook) {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/attachments"
    }

    companion object {
        val bottomBar: List<Destination> = listOf(Collectives, Search, Favorites, Settings)
    }
}
