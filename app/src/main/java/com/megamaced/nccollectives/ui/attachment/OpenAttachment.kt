package com.megamaced.nccollectives.ui.attachment

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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

/**
 * Opens [uri] in a Custom Tab, and says whether anything took it.
 *
 * Issue #26: a device with no enabled browser — routine in managed and kiosk
 * deployments — throws `ActivityNotFoundException` out of `launchUrl`. Two of
 * the three places that hand a URI to the system already caught it; the
 * markdown link handler didn't, so tapping an external link there crashed
 * out of the tap handler. The inconsistency is the tell: it was missed, not
 * decided.
 *
 * Returning false rather than throwing keeps the caller's choice about what
 * the user sees, which is the same shape [openAttachmentExternally] uses.
 */
fun openInBrowser(
    context: Context,
    uri: Uri,
): Boolean =
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No browser on this device to open %s", uri)
        false
    }

/**
 * Opens [uri] with whatever app claims it, and says whether one did. The
 * non-http(s) sibling of [openInBrowser]; same reasoning (issue #26).
 */
fun openWithSystemHandler(
    context: Context,
    uri: Uri,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No handler on this device for %s", uri)
        false
    }
