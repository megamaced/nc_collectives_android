package com.megamaced.nccollectives.domain.model

/**
 * Result of a page-body save attempt. Distinguishes the offline-queued
 * happy-path ([Queued]) from a real success-on-the-server ([Saved]) and
 * from an etag race that needs user attention ([Conflict]).
 */
sealed interface SaveOutcome {
    /** Body PUT to the server and persisted locally. */
    data object Saved : SaveOutcome

    /** No network — enqueued for `EditFlushWorker` to drain when online. */
    data object Queued : SaveOutcome

    /**
     * Server's body changed since the etag we held. Server version is kept
     * authoritative; the user's pending body is stored as a draft on the
     * page and surfaced through `ConflictBanner` on next open.
     */
    data object Conflict : SaveOutcome

    data class Error(
        val message: String,
    ) : SaveOutcome
}
