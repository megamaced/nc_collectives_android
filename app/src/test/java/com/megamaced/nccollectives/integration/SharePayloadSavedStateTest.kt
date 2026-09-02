package com.megamaced.nccollectives.integration

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #42: an unfinished share has to survive the process, not just the
 * Activity.
 *
 * `SharePayloadHolder` is a process-local singleton, and B-74's
 * `savedInstanceState == null` gate turned that into data loss rather than
 * merely a cold start. When Android reclaims the background process while
 * capture, destination selection, or the login browser is foreground, the
 * task is restored with a non-null saved state, so nothing republishes — and
 * the `EXTRA_SHARE_HANDLED` marker that would have permitted it does not
 * survive either, because `putExtra` mutates *this process's* copy of the
 * intent while the system re-delivers its own. The user's text and images
 * were still sitting in the restored intent, unreachable.
 *
 * A real [Bundle], parcelled and unparcelled, rather than a map stand-in:
 * saved state crosses a Binder boundary, and "it round-trips in memory" is
 * not the claim that matters.
 */
@RunWith(AndroidJUnit4::class)
class SharePayloadSavedStateTest {
    @Test
    fun textAndImagesSurviveARealParcelRoundTrip() {
        val original = SharePayload(
            subject = "A headline",
            text = "the shared snippet",
            images = listOf(
                Uri.parse("content://media/external/images/media/1"),
                Uri.parse("content://media/external/images/media/2"),
            ),
        )

        val restored = SharePayload.fromSavedState(reparcel(original))

        assertEquals(original, restored)
    }

    @Test
    fun theShareKeepsItsIdentity() {
        // `consume(payloadId)` and the scaffold's identity keying both compare
        // ids, so a regenerated one would make the recovered share look like
        // a second, different share.
        val original = SharePayload(text = "hello")

        assertEquals(original.id, SharePayload.fromSavedState(reparcel(original))?.id)
    }

    @Test
    fun aBundleWithNoPendingShareRestoresNothing() {
        // The ordinary case: the user finished the share, `consume` emptied
        // the holder, and there was nothing to save. This is what keeps B-74
        // fixed — a finished share must not be replayed on the next launch.
        assertNull(SharePayload.fromSavedState(Bundle()))
    }

    @Test
    fun aFileUriInTheBundleIsRefused() {
        // S-11 restated at the boundary it crosses. The URIs went out as
        // strings and come back as strings, and a `file://` path would bypass
        // the ContentResolver permission checks the whole upload pipeline
        // assumes.
        val state = Bundle()
        SharePayload(text = "note", images = listOf(Uri.parse("content://ok/1"))).writeTo(state)
        state.putStringArrayList(
            "com.megamaced.nccollectives.PENDING_SHARE_IMAGES",
            arrayListOf("file:///data/data/com.megamaced.nccollectives/databases/secrets.db"),
        )

        val restored = SharePayload.fromSavedState(reparcel(state))

        assertNotNull("the text is still a share worth recovering", restored)
        assertTrue("the file:// URI must be dropped", restored!!.images.isEmpty())
    }

    @Test
    fun aBundleWhoseOnlyContentWasAFileUriRestoresNothing() {
        val state = Bundle()
        SharePayload(images = listOf(Uri.parse("content://ok/1"))).writeTo(state)
        state.putStringArrayList(
            "com.megamaced.nccollectives.PENDING_SHARE_IMAGES",
            arrayListOf("file:///etc/passwd"),
        )

        assertNull(SharePayload.fromSavedState(reparcel(state)))
    }

    @Test
    fun anUnfinishedShareIsSavedAndAnEmptyHolderIsNot() {
        // The rule the whole fix rests on: what goes into the bundle is what
        // the holder is *still* holding, so "unfinished" needs no separate
        // bookkeeping — `ShareCaptureViewModel` calling `consume` is already
        // the record that the content reached the server.
        val holder = SharePayloadHolder()
        val payload = SharePayload(text = "half-written")
        holder.publish(payload)

        val whileUnfinished = Bundle().also { holder.payload.value?.writeTo(it) }
        holder.consume(payload.id)
        val afterFinishing = Bundle().also { holder.payload.value?.writeTo(it) }

        assertEquals(payload.id, SharePayload.fromSavedState(reparcel(whileUnfinished))?.id)
        assertNull(SharePayload.fromSavedState(reparcel(afterFinishing)))
    }

    @Test
    fun aRecoveredShareCanBeRepublishedAndThenConsumedNormally() {
        // End to end through the holder: what a restored process does.
        val holder = SharePayloadHolder()
        val original = SharePayload(text = "recovered")
        val state = Bundle().also { original.writeTo(it) }

        val restored = SharePayload.fromSavedState(reparcel(state))!!
        holder.publish(restored)
        assertEquals("recovered", holder.payload.value?.text)

        holder.consume(restored.id)
        assertNull(holder.payload.value)
    }

    private fun reparcel(payload: SharePayload): Bundle = reparcel(Bundle().also { payload.writeTo(it) })

    /** Push [source] through a real `Parcel`, as the system does. */
    private fun reparcel(source: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(source)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(SharePayload::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
