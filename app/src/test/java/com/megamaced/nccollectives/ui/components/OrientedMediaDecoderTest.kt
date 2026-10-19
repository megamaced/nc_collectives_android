package com.megamaced.nccollectives.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/**
 * Issue #64: a photo stored sideways with an EXIF "rotate 90°" tag is shown
 * upright. Real decoding, so native graphics.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrientedMediaDecoderTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aPhotoTaggedToBeTurnedIsShownTurned() {
        val photo = sidewaysJpeg(width = 40, height = 20)
        // The fixture really is stored sideways: what BitmapFactory, and so
        // Markwon's default decoder, would have shown.
        val raw = BitmapFactory.decodeByteArray(photo, 0, photo.size)
        assertEquals(40, raw.width)

        val drawable =
            OrientedMediaDecoder(ApplicationProvider.getApplicationContext<android.app.Application>().resources)
                .decode("image/jpeg", ByteArrayInputStream(photo))

        assertEquals(20, drawable.intrinsicWidth)
        assertEquals(40, drawable.intrinsicHeight)
    }

    private fun sidewaysJpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val file = folder.newFile("photo.jpg")
        file.outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes()
    }
}
