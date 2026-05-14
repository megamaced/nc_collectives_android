package com.megamaced.nccollectives.ui.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Resolves a user-facing filename for a `content://` (or other) [Uri] by
 * querying `OpenableColumns.DISPLAY_NAME`; falls back to the URI's last path
 * segment when the provider doesn't expose a display name.
 *
 * Used by both the camera-capture flow and the system-picker flow when
 * uploading attachments.
 */
internal fun uriDisplayName(
    context: Context,
    uri: Uri,
): String? {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    return uri.lastPathSegment
}
