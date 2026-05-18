package com.megamaced.nccollectives.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Parsed `ACTION_SEND` / `ACTION_SEND_MULTIPLE` content the user fed in
 * from another app. Text and images may both be present (e.g. a browser
 * sharing a snippet + screenshot), so we keep them as separate fields
 * rather than a sealed hierarchy.
 */
data class SharePayload(
    val subject: String? = null,
    val text: String? = null,
    val images: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && images.isEmpty()

    companion object {
        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent): SharePayload? {
            val action = intent.action ?: return null
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }
            val images = when (action) {
                Intent.ACTION_SEND -> {
                    val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    listOfNotNull(stream)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent
                            .getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            .orEmpty()
                    } else {
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    }
                }
                else -> emptyList()
            }
            // Some senders pack extras into ClipData (e.g. Chrome). Honour
            // those if EXTRA_STREAM is empty.
            val clipImages = if (images.isEmpty()) intent.clipData?.imageUris().orEmpty() else emptyList()
            // S-11: only accept `content://` URIs. A `file://` URI would
            // bypass ContentResolver permission checks and let a malicious
            // co-installed app target this exported Activity with any path
            // readable by our UID (the wider Files area, internal-storage
            // databases, etc.) and exfiltrate it into the user's Nextcloud
            // via the upload pipeline. The OS-level intent-filter scheme
            // restriction in AndroidManifest.xml is the first line of
            // defence; this is the second.
            val combined = (images + clipImages).filter { it.scheme == "content" }
            val payload = SharePayload(subject = subject, text = text, images = combined)
            return payload.takeUnless { it.isEmpty }
        }

        private fun ClipData.imageUris(): List<Uri> =
            buildList {
                for (i in 0 until itemCount) {
                    getItemAt(i)?.uri?.let { add(it) }
                }
            }
    }
}
