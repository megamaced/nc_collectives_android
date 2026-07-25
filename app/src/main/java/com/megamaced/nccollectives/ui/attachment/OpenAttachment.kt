package com.megamaced.nccollectives.ui.attachment

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.megamaced.nccollectives.domain.model.OpenableAttachment
import timber.log.Timber

/**
 * Hands a downloaded attachment to whichever app the user has for its type.
 *
 * Returns false when nothing on the device can open it, so the caller can
 * say so rather than leaving a tap that silently does nothing — the failure
 * mode this whole path exists to remove.
 */
fun openAttachmentExternally(
    context: Context,
    attachment: OpenableAttachment,
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        // setDataAndType, not setData then setType — the latter clears the
        // data URI, and a viewer chooser needs both.
        setDataAndType(attachment.uri, attachment.mimeType ?: "*/*")
        // The receiving app gets a read grant on our FileProvider URI for
        // the lifetime of its task, and nothing else.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No app can open %s (%s)", attachment.fileName, attachment.mimeType)
        false
    }
}
