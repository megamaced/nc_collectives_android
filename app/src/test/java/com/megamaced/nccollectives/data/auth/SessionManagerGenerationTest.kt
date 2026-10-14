package com.megamaced.nccollectives.data.auth

import dagger.Lazy
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way a session change ends settles the account generation, so the
 * window `LocalDataWiper` opened never stays open — which would refuse every
 * write and every bound request for the rest of the process.
 */
class SessionManagerGenerationTest {
    private val tokenStore = mockk<TokenStore>(relaxed = true) {
        every { accounts() } returns emptyList()
        every { activeAccountId() } returns null
        every { getCredentials() } returns null
        justRun { clear() }
    }
    private val generation = AccountGeneration()
    private val sessionManager = SessionManager(tokenStore, Lazy { mockk<ExpiredSessionHandler>() }, generation)

    private fun midSwitch(): Long {
        generation.invalidate()
        val during = generation.current()
        assertFalse(generation.isCurrent(during))
        return during
    }

    @Test
    fun aFinishedSwitchSettles() {
        val during = midSwitch()
        sessionManager.beginAccountSwitch()

        sessionManager.endAccountSwitch()

        assertTrue(generation.isCurrent(generation.current()))
        assertFalse(generation.isCurrent(during))
    }

    @Test
    fun aFinishedSignOutSettles() {
        midSwitch()
        sessionManager.beginSignOut()

        sessionManager.endSignOut()

        assertTrue(generation.isCurrent(generation.current()))
    }

    @Test
    fun aSignInIsAFreshGeneration() {
        val before = generation.current()

        sessionManager.onLoginSuccess("https://cloud.example", "alice", "password")

        assertFalse(generation.isCurrent(before))
        assertTrue(generation.isCurrent(generation.current()))
    }
}
