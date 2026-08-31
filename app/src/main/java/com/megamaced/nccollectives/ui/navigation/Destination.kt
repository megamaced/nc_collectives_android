package com.megamaced.nccollectives.ui.navigation

internal sealed class Destination(
    val route: String,
) {
    object Collectives : Destination("collectives")

    object Search : Destination("search")

    object Favorites : Destination("favorites")

    object Settings : Destination("settings")

    object PageTree : Destination("pageTree/{collectiveId}") {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun route(collectiveId: Long) = "pageTree/$collectiveId"
    }

    object PageView : Destination("page/{pageId}") {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId"
    }

    object PageEdit : Destination("page/{pageId}/edit") {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/edit"
    }

    /**
     * Batch 28 — Nextcloud Text WebView editor reached via the Files
     * `directediting` OCS endpoint. Behind a debug-only entry point on
     * `PageViewScreen` until Batch 29 lands the production routing
     * setting. Native [PageEdit] remains the offline / older-server
     * fallback.
     */
    object PageEditWeb : Destination("page/{pageId}/edit-web") {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/edit-web"
    }

    object Attachments : Destination("page/{pageId}/attachments") {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/attachments"
    }

    /**
     * "Add account" (issue #14) — the same `LoginScreen` the scaffold shows
     * when signed out, reached from Settings while a session is live. On
     * success `AccountSwitcher` flips the session through
     * `AuthState.Switching`, which unmounts this whole nav host, so nothing
     * here has to pop the destination itself.
     */
    object AddAccount : Destination("account/add")

    /** Reached from share intents, not from the UI. */
    object ShareCapture : Destination("share")

    object Trash : Destination("collective/{collectiveId}/trash") {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun route(collectiveId: Long) = "collective/$collectiveId/trash"
    }

    /** App-wide collectives trash (Batch 22). Distinct from per-collective page trash. */
    object CollectiveTrash : Destination("collectives/trash")

    /**
     * Browse pages by tag (Batch 25). Carries the tag *name* rather than
     * the tag id because the local cache (`PageEntity.tagsCsv`) stores
     * names, and the app can't rename tags so the name is stable as a
     * route arg. Tag names can contain `/` or spaces, so callers must
     * URL-encode via [route].
     */
    object TagBrowse : Destination("collective/{collectiveId}/tag/{tagName}") {
        const val ARG_COLLECTIVE_ID = "collectiveId"
        const val ARG_TAG_NAME = "tagName"

        fun route(
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
            return "collective/$collectiveId/tag/$encoded"
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
    object Members : Destination("collective/{collectiveId}/members") {
        const val ARG_COLLECTIVE_ID = "collectiveId"

        fun route(collectiveId: Long) = "collective/$collectiveId/members"
    }
}
