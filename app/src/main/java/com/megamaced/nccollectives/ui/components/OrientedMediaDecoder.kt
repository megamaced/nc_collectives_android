package com.megamaced.nccollectives.ui.components

import android.content.res.Resources
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import io.noties.markwon.image.MediaDecoder
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Markwon's image decoder, through [ImageDecoder] rather than the default's
 * `BitmapFactory`.
 *
 * Issue #64: `BitmapFactory` ignores a photo's EXIF orientation, so a camera
 * picture stored sideways with a "rotate 90°" tag — routine for portrait
 * shots — showed on its side here, while browsers, the Nextcloud web app
 * and the gallery all turn it upright. [ImageDecoder] applies the
 * orientation as they do.
 */
internal class OrientedMediaDecoder(
    private val resources: Resources,
) : MediaDecoder() {
    override fun decode(
        contentType: String?,
        inputStream: InputStream,
    ): Drawable {
        val bytes = inputStream.readBytes()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            // A software bitmap, as BitmapFactory's was: Markwon draws it
            // wherever the text is drawn, software canvases included.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return bitmap.toDrawable(resources)
    }

    /** The fallback for every type, as the default decoder is. */
    override fun supportedTypes(): Collection<String> = emptySet()
}
