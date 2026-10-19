package com.megamaced.nccollectives.data.auth

import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #58: a run of 401s expires the account its requests were made for,
 * never the account that happens to be active when they come back.
 */
class DelayedAuthFailureTest {
    private val tokenStore = mockk<TokenStore>(relaxed = true) {
        every { accounts() } returns emptyList()
        every { activeAccountId() } returns "bob"
    }
    private val generation = AccountGeneration()
    private val expired = mutableListOf<String>()
    private val sessionManager =
        SessionManager(
            tokenStore,
            Lazy {
                object : ExpiredSessionHandler {
                    override fun onSessionExpired(accountId: String) {
                        expired += accountId
                    }
                }
            },
            generation,
        )

    @Test
    fun theOutgoingAccountsLate401sExpireNobody_andDoNotCountAgainstTheNextAccount() {
        val alices = generation.current()
        // The switch to Bob, complete before Alice's answers arrive.
        generation.invalidate()
        generation.settle()

        sessionManager.onAuthenticatedResponse(401, alices)
        sessionManager.onAuthenticatedResponse(401, alices)
        assertTrue(expired.isEmpty())

        // Bob's own streak starts from nothing: one 401 is not a streak.
        sessionManager.onAuthenticatedResponse(401, generation.current())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun oneLate401FromTheOutgoingAccountDoesNotHalveTheNextAccountsAllowance() {
        val alices = generation.current()
        generation.invalidate()
        generation.settle()

        sessionManager.onAuthenticatedResponse(401, alices)
        sessionManager.onAuthenticatedResponse(401, generation.current())

        assertTrue("one 401 of Bob's own is not a streak", expired.isEmpty())
    }

    @Test
    fun theCurrentAccountsOwn401sStillExpireIt() {
        val bobs = generation.current()

        sessionManager.onAuthenticatedResponse(401, bobs)
        sessionManager.onAuthenticatedResponse(401, bobs)

        assertEquals(listOf("bob"), expired)
    }

    @Test
    fun a401DuringASwitchCountsForNothing() {
        generation.invalidate()
        val during = generation.current()

        sessionManager.onAuthenticatedResponse(401, during)
        sessionManager.onAuthenticatedResponse(401, during)

        assertTrue(expired.isEmpty())
    }
}
