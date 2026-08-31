package com.megamaced.nccollectives.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountIdentityTest {
    @Test
    fun `the same login on the same server is the same account`() {
        // This is what makes signing in again an update rather than a
        // duplicate row in the switcher.
        assertEquals(
            accountIdOf("https://cloud.example.com", "alice"),
            accountIdOf("https://cloud.example.com", "alice"),
        )
    }

    @Test
    fun `a trailing slash on the server is not a different account`() {
        // Nextcloud's login flow returns `server` with and without the
        // trailing slash depending on how the instance is configured, so
        // without this a re-login could strand a second copy of the account.
        assertEquals(
            accountIdOf("https://cloud.example.com", "alice"),
            accountIdOf("https://cloud.example.com/", "alice"),
        )
    }

    @Test
    fun `the same login on different servers is two accounts`() {
        assertNotEquals(
            accountIdOf("https://cloud.example.com", "alice"),
            accountIdOf("https://other.example.com", "alice"),
        )
    }

    @Test
    fun `different logins on one server are two accounts`() {
        assertNotEquals(
            accountIdOf("https://cloud.example.com", "alice"),
            accountIdOf("https://cloud.example.com", "bob"),
        )
    }
}

class NextActiveAfterRemovalTest {
    private val ids = listOf("alice@a", "bob@b", "carol@c")

    @Test
    fun `removing an inactive account leaves the active one alone`() {
        assertEquals(
            "alice@a",
            nextActiveAfterRemoval(accountIds = ids, activeId = "alice@a", removedId = "carol@c"),
        )
    }

    @Test
    fun `removing the active account promotes the next one`() {
        assertEquals(
            "bob@b",
            nextActiveAfterRemoval(accountIds = ids, activeId = "alice@a", removedId = "alice@a"),
        )
    }

    @Test
    fun `removing the active account promotes a survivor that precedes it`() {
        assertEquals(
            "alice@a",
            nextActiveAfterRemoval(accountIds = ids, activeId = "carol@c", removedId = "carol@c"),
        )
    }

    @Test
    fun `removing the last account signs out`() {
        assertNull(
            nextActiveAfterRemoval(accountIds = listOf("alice@a"), activeId = "alice@a", removedId = "alice@a"),
        )
    }
}
