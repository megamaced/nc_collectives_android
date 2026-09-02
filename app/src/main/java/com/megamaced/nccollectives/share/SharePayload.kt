package com.megamaced.nccollectives.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.util.UUID

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
    /**
     * Identity of this share, distinct from its content (issue #25).
     *
     * Two things need it. `SharePayloadHolder.consume` takes it so a save
     * finishing can only clear the payload it was actually handling —
     * unconditionally nulling the field meant a share arriving while an
     * earlier one was still saving was discarded by that earlier one's
     * completion. And B-80's identity keying in `NcCollectivesScaffold`
     * only re-fires on a payload that isn't `==` the last one, so without
     * an id two shares of the same text were indistinguishable and the
     * second was dropped before anything noticed it.
     *
     * Fresh per construction, and preserved by `copy`, which is what makes
     * a re-emission of the same payload compare equal while a genuinely new
     * share never does.
     */
    val id: String = UUID.randomUUID().toString(),
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && images.isEmpty()

    /**
     * Save this payload into an Activity's saved instance state so it can
     * outlive the process — issue #42.
     *
     * Written as primitives rather than as a `Parcelable` so the bundle's
     * contents stay legible to anything reading a bug report, and so adding
     * a field to this class cannot silently change what an old bundle
     * deserialises to.
     */
    fun writeTo(state: Bundle) {
        state.putString(KEY_ID, id)
        state.putString(KEY_SUBJECT, subject)
        state.putString(KEY_TEXT, text)
        state.putStringArrayList(KEY_IMAGES, ArrayList(images.map(Uri::toString)))
    }

    companion object {
        private const val KEY_ID = "com.megamaced.nccollectives.PENDING_SHARE_ID"
        private const val KEY_SUBJECT = "com.megamaced.nccollectives.PENDING_SHARE_SUBJECT"
        private const val KEY_TEXT = "com.megamaced.nccollectives.PENDING_SHARE_TEXT"
        private const val KEY_IMAGES = "com.megamaced.nccollectives.PENDING_SHARE_IMAGES"

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

                else -> {
                    emptyList()
                }
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

        /**
         * Read back what [writeTo] saved, or null when the bundle holds no
         * pending share — issue #42.
         *
         * The payload's own [id] is restored rather than regenerated, so a
         * share recovered after process death is still the *same* share as
         * far as `SharePayloadHolder.consume` and the scaffold's identity
         * keying are concerned.
         *
         * S-11 is re-asserted rather than assumed: the URIs went out as
         * strings and come back as strings, and the invariant that keeps
         * `file://` paths out of the upload pipeline is worth restating at
         * every boundary they cross.
         */
        fun fromSavedState(state: Bundle): SharePayload? {
            val id = state.getString(KEY_ID) ?: return null
            val payload = SharePayload(
                subject = state.getString(KEY_SUBJECT),
                text = state.getString(KEY_TEXT),
                images = state
                    .getStringArrayList(KEY_IMAGES)
                    .orEmpty()
                    .map(Uri::parse)
                    .filter { it.scheme == "content" },
                id = id,
            )
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
