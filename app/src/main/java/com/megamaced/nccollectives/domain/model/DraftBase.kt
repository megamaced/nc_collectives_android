package com.megamaced.nccollectives.domain.model

import java.security.MessageDigest

/**
 * What an editor's text was written from: the page's text as the editor
 * was filled with it, and the server version holding that text when one is
 * known (issue #57).
 *
 * The text is kept as a digest — the draft beside it can run to megabytes,
 * and both live in saved state. A save is written against whichever version
 * still holds that text: the cached one when it does, which is the version
 * the server will match; otherwise [etag], which it won't, so the save is a
 * conflict and the draft is kept. Null [etag] means no server version was
 * known to hold the text: it was the user's queued edit, or the server sends
 * no ETags.
 */
data class DraftBase(
    val etag: String?,
    val textDigest: String,
) {
    companion object {
        /** The [DraftBase] of an editor filled with [text] from version [etag]. */
        fun of(
            text: String,
            etag: String?,
        ): DraftBase = DraftBase(etag, digestOf(text))

        /** SHA-256 of [text]'s UTF-8 bytes, in hex. */
        fun digestOf(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
