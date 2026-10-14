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
    fun `a write captured once the switch has landed may commit`() {
        // The incoming account's own sync must not be blocked by the wipe
        // that made room for it.
        val generation = AccountGeneration()
        generation.invalidate()
        generation.settle()
        val captured = generation.current()
        assertTrue(generation.isCurrent(captured))
    }

    @Test
    fun `a write captured while a switch is under way may never commit`() {
        // Mid-switch the outgoing account's credentials and cache can still
        // be in place; work started then would read one account and land in
        // the next once it is settled.
        val generation = AccountGeneration()
        generation.invalidate()
        val duringSwitch = generation.current()
        assertFalse(generation.isCurrent(duringSwitch))
        generation.settle()
        assertFalse(generation.isCurrent(duringSwitch))
    }

    @Test
    fun `settling is a fresh start even without a switch`() {
        // A sign-in with nothing signed in changes the credentials too.
        val generation = AccountGeneration()
        val captured = generation.current()
        generation.settle()
        assertFalse(generation.isCurrent(captured))
        assertTrue(generation.isCurrent(generation.current()))
    }

    @Test
    fun `two switches in a row keep the window open until one lands`() {
        val generation = AccountGeneration()
        generation.invalidate()
        generation.invalidate()
        assertFalse(generation.isCurrent(generation.current()))
        generation.settle()
        assertTrue(generation.isCurrent(generation.current()))
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
