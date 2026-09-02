package com.megamaced.nccollectives.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #20. Small enough that the tests are really documentation of the
 * contract every guarded write depends on: capture before the request,
 * compare inside the transaction that writes its response.
 */
class AccountGenerationTest {
    @Test
    fun `a write captured with nothing in between may commit`() {
        val generation = AccountGeneration()
        val captured = generation.current()
        assertTrue(generation.isCurrent(captured))
    }

    @Test
    fun `a write captured before a wipe may not commit`() {
        val generation = AccountGeneration()
        val captured = generation.current()
        generation.invalidate()
        assertFalse(generation.isCurrent(captured))
    }

    @Test
    fun `a write captured after a wipe may commit`() {
        // The incoming account's own sync must not be blocked by the wipe
        // that made room for it.
        val generation = AccountGeneration()
        generation.invalidate()
        val captured = generation.current()
        assertTrue(generation.isCurrent(captured))
    }

    @Test
    fun `a stale capture never becomes current again`() {
        // Monotonic, not a toggle: two switches in a row must not hand the
        // first switch's abandoned writes a second chance.
        val generation = AccountGeneration()
        val captured = generation.current()
        generation.invalidate()
        generation.invalidate()
        assertFalse(generation.isCurrent(captured))
    }
}
