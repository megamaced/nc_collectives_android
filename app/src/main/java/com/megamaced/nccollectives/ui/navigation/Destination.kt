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

    object Attachments : Destination("page/{pageId}/attachments") {
        const val ARG_PAGE_ID = "pageId"

        fun route(pageId: Long) = "page/$pageId/attachments"
    }

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
}
