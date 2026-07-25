package com.megamaced.nccollectives.domain.model

import android.net.Uri

/**
 * A page attachment staged in the app's cache and ready to hand to another
 * app via `ACTION_VIEW`.
 *
 * [uri] is a `content://` URI from the app's own `FileProvider` (never a
 * `file://` path — those have been unshareable since API 24) and carries
 * only a read grant. [mimeType] is the server's `Content-Type` where it
 * gave one, falling back to an extension guess, because the receiving app
 * picks its viewer from the type and a wrong guess opens the wrong app.
 */
data class OpenableAttachment(
    val uri: Uri,
    val fileName: String,
    val mimeType: String?,
)
