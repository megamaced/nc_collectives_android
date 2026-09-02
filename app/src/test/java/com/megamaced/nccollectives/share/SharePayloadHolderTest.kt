package com.megamaced.nccollectives.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #25: `consume()` nulled the payload unconditionally, so a share
 * arriving while an earlier one was still saving was discarded by that
 * earlier one's completion. B-80 had already made the *arrival* of a second
 * share observable by keying on payload identity; the clear was never given
 * the same treatment.
 */
class SharePayloadHolderTest {
    @Test
    fun `consuming the held payload clears it`() {
        val holder = SharePayloadHolder()
        val payload = SharePayload(text = "hello")
        holder.publish(payload)
        holder.consume(payload.id)
        assertNull(holder.payload.value)
    }

    @Test
    fun `consuming an older payload leaves a newer one alone`() {
        val holder = SharePayloadHolder()
        val first = SharePayload(text = "first")
        val second = SharePayload(text = "second")
        holder.publish(first)
        holder.publish(second)
        // First share's save completes after the second arrived.
        holder.consume(first.id)
        assertEquals(second, holder.payload.value)
    }

    @Test
    fun `discard clears whatever is held`() {
        // S-16: a share captured under one account must not survive into
        // the next one's session, and there is no id to match against there.
        val holder = SharePayloadHolder()
        holder.publish(SharePayload(text = "hello"))
        holder.discard()
        assertNull(holder.payload.value)
    }

    @Test
    fun `two shares of the same text are different payloads`() {
        // Without an identity of its own, the second was `==` the first, so
        // the scaffold's identity keying never re-fired and the arrival was
        // dropped before anything noticed it.
        assertNotEquals(SharePayload(text = "same"), SharePayload(text = "same"))
    }

    @Test
    fun `copying a payload preserves its identity`() {
        // What makes a re-emission of the same payload compare equal.
        val payload = SharePayload(text = "hello")
        assertEquals(payload.id, payload.copy(text = "hello").id)
    }
}
