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
}
