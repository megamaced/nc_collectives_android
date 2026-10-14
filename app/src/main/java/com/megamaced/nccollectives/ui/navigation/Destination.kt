package com.megamaced.nccollectives.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import com.megamaced.nccollectives.ui.screen.home.ALL_COLLECTIVES
import com.megamaced.nccollectives.ui.screen.home.HomeSection

/**
 * The five top-level destinations, in navigation-bar order.
 *
 * Each tab is a nested graph ([graphRoute]) with its own back stack, which
 * the navigation bar saves and restores as the user moves between tabs. The
 * detail screens a tab can reach are registered inside its graph under routes
 * prefixed with [prefix]. A page opened from Search therefore lives on the
 * Search stack, under `search/page/{pageId}`, and the selected tab can be read
 * from the destination hierarchy alone.
 */
internal enum class TopLevelTab(
    val prefix: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    Collectives("collectives", "Collectives", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    Search("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    Favorites("favorites", "Favorites", Icons.Filled.Star, Icons.Outlined.StarBorder),
    Alerts("alerts", "Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    ;

    /** The nested graph holding this tab's back stack. */
    val graphRoute: String get() = "$prefix-tab"

    /** The tab's own screen, at the bottom of its back stack. */
    val rootRoute: String get() = "$prefix/root"
}

/**
 * Detail destinations. Each is registered once per tab, so its route
 * [pattern] and the concrete route built by `route(...)` both carry the tab
 * prefix. The argument names are the same on every tab, which is what lets
 * each screen's ViewModel read them from its `SavedStateHandle` without
 * knowing which tab it was opened in.
 */
internal object Destination {
    /** Reached from the Home tab's settings action. */
    object Settings {
        val route: String = "${TopLevelTab.Home.prefix}/settings"
    }

    /**
     * One of Home's sections in full, behind its "See all". Home-only: the
     * sections are Home's. [ARG_COLLECTIVE] carries the collective filter
     * "Recently updated" was showing, `0` for every collective.
     */
    object HomeSeeAll {
        const val ARG_SECTION = "section"
        const val ARG_COLLECTIVE = "collective"
        val pattern: String = "${TopLevelTab.Home.prefix}/section/{$ARG_SECTION}?$ARG_COLLECTIVE={$ARG_COLLECTIVE}"

        fun route(
            section: HomeSection,
            collectiveId: Long?,
        ): String = "${TopLevelTab.Home.prefix}/section/${section.key}?$ARG_COLLECTIVE=${collectiveId ?: ALL_COLLECTIVES}"
    }

    /**
     * "Add account" (issue #14) — the same `LoginScreen` the scaffold shows
     * when signed out, reached from Settings while a session is live. On
     * success `AccountSwitcher` flips the session through
     * `AuthState.Switching`, which unmounts this whole nav host, so nothing
     * here has to pop the destination itself.
     */
    object AddAccount {
        val route: String = "${TopLevelTab.Home.prefix}/account/add"
    }

    /**
     * Reached from share intents, not from the UI. Registered outside every
     * tab graph and pushed on top of whichever stack is showing, so
     * dismissing it returns to exactly where the user was.
     */
    object ShareCapture {
        const val ROUTE = "share"
    }

    object PageTree {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/collective/{$ARG_COLLECTIVE_ID}"

        fun route(
            tab: TopLevelTab,
            collectiveId: Long,
        ) = "${tab.prefix}/collective/$collectiveId"
    }

    object PageView {
        const val ARG_PAGE_ID = "pageId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/page/{$ARG_PAGE_ID}"

        fun route(
            tab: TopLevelTab,
            pageId: Long,
        ) = "${tab.prefix}/page/$pageId"
    }

    object PageEdit {
        const val ARG_PAGE_ID = "pageId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/page/{$ARG_PAGE_ID}/edit"

        fun route(
            tab: TopLevelTab,
            pageId: Long,
        ) = "${tab.prefix}/page/$pageId/edit"
    }

    /**
     * Nextcloud Text WebView editor reached via the Files `directediting`
     * OCS endpoint. Native [PageEdit] is the offline / older-server
     * fallback.
     */
    object PageEditWeb {
        const val ARG_PAGE_ID = "pageId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/page/{$ARG_PAGE_ID}/edit-web"

        fun route(
            tab: TopLevelTab,
            pageId: Long,
        ) = "${tab.prefix}/page/$pageId/edit-web"
    }

    object Attachments {
        const val ARG_PAGE_ID = "pageId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/page/{$ARG_PAGE_ID}/attachments"

        fun route(
            tab: TopLevelTab,
            pageId: Long,
        ) = "${tab.prefix}/page/$pageId/attachments"
    }

    /** A collective's page trash. */
    object Trash {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/collective/{$ARG_COLLECTIVE_ID}/trash"

        fun route(
            tab: TopLevelTab,
            collectiveId: Long,
        ) = "${tab.prefix}/collective/$collectiveId/trash"
    }

    /** App-wide collectives trash (Batch 22). Distinct from per-collective page trash. */
    object CollectiveTrash {
        fun route(tab: TopLevelTab) = "${tab.prefix}/collectives/trash"
    }

    /**
     * Browse pages by tag (Batch 25). Carries the tag *name* rather than
     * the tag id because the local cache (`PageEntity.tagsCsv`) stores
     * names, and the app can't rename tags so the name is stable as a
     * route arg. Tag names can contain `/` or spaces, so callers must
     * URL-encode via [route].
     *
     * [ALL_COLLECTIVES] in place of a collective id browses the tag across
     * every collective. Nextcloud ids start at 1, and the value never leaves
     * the screen: it picks which cached pages to list and is never sent to
     * the server.
     */
    object TagBrowse {
        const val ARG_COLLECTIVE_ID = "collectiveId"
        const val ARG_TAG_NAME = "tagName"
        const val ALL_COLLECTIVES = 0L

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/collective/{$ARG_COLLECTIVE_ID}/tag/{$ARG_TAG_NAME}"

        fun route(
            tab: TopLevelTab,
            collectiveId: Long,
            tagName: String,
        ): String {
            // B-33: `URLEncoder.encode` produces form-encoded output where
            // a space becomes `+`; Compose Navigation reads `+` literally
            // out of a path argument, so a tag named `to read` would
            // round-trip as `to+read` and the Browse screen would show
            // zero results. `android.net.Uri.encode` is the path-encoding
            // variant — spaces become `%20`. The NavType.StringType
            // decoder un-escapes percent encoding for us.
            val encoded = android.net.Uri.encode(tagName)
            return "${tab.prefix}/collective/$collectiveId/tag/$encoded"
        }
    }

    /**
     * Members of the Nextcloud Team backing a collective (B-90).
     *
     * Carries the *collective* id, not the `circleId` the Circles API is
     * keyed on, for two reasons. `Collective.circleId` is nullable, so a
     * route built from it could not be constructed for every collective —
     * the caller would have to decide what "no team" means before it could
     * even navigate. And the id every other route here already uses is the
     * collective's, so `PageTreeScreen` needs nothing new to reach this.
     * `MembersViewModel` resolves the circle id from the cached collective,
     * which also lets it pick the id up if it arrives *after* the screen
     * opens — a cache row written before `MIGRATION_8_9` has no circleId
     * until the next `refresh()`.
     */
    object Members {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun pattern(tab: TopLevelTab) = "${tab.prefix}/collective/{$ARG_COLLECTIVE_ID}/members"

        fun route(
            tab: TopLevelTab,
            collectiveId: Long,
        ) = "${tab.prefix}/collective/$collectiveId/members"
    }
}
