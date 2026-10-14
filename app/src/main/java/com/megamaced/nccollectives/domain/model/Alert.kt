package com.megamaced.nccollectives.domain.model

/** What happened, as far as the app tells alerts apart. */
enum class AlertKind {
    /** Someone mentioned the user in a page. */
    Mention,

    /** Someone changed a page, in a collective set to notify about all changes. */
    PageUpdated,

    /** Someone deleted a page, likewise. */
    PageDeleted,
}

/**
 * A Nextcloud notification about a collective page, as the Alerts tab shows
 * it. Nextcloud keeps no read flag, so [seen] is this device's own: whether
 * the Alerts tab has shown it since it arrived.
 */
data class Alert(
    val id: Long,
    val kind: AlertKind,
    val pageId: Long,
    /** Null when the server didn't say; the page, if cached, still knows. */
    val collectiveId: Long?,
    val actorName: String?,
    val pageTitle: String?,
    val collectiveName: String?,
    /** The server's own sentence for it, in the user's language. */
    val subject: String,
    /** Seconds since the epoch. */
    val timestamp: Long,
    val seen: Boolean,
)

/**
 * How much a collective notifies the user about, as the web app's
 * per-collective setting has it. [wire] is the server's value.
 */
enum class NotifyLevel(
    val wire: Int,
) {
    Off(0),
    Mentions(1),
    All(2),
    ;

    companion object {
        /** Null for a server that doesn't report the setting, or a value this app doesn't know. */
        fun fromWire(wire: Int?): NotifyLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/** What dismissing every alert came to: how many went, and how many the server refused. */
data class DismissAllResult(
    val dismissed: Int,
    val failed: Int,
)
