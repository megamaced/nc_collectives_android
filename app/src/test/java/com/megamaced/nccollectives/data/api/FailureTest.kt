package com.megamaced.nccollectives.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.AccountChangedException
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A [Failure] is stored by name and worded when shown, so it has to come
 * back out of storage as itself — and each one has to have words.
 */
@RunWith(AndroidJUnit4::class)
class FailureTest {
    private val every: List<Failure> =
        listOf(
            Failure.Network,
            Failure.Http(500),
            Failure.SessionExpired,
            Failure.Conflict,
            Failure.Unexpected("disk: full: really"),
            Failure.Unexpected(null),
        ) + AppFailure.entries.map(Failure::App)

    @Test
    fun everyFailureComesBackFromStorageAsItself() {
        every.forEach { assertEquals(it, Failure.decode(it.encode())) }
    }

    @Test
    fun whatIsStoredIsANameNotAResourceId() {
        assertEquals("app:NotOnDevice", Failure.App(AppFailure.NotOnDevice).encode())
        assertEquals("http:404", Failure.Http(404).encode())
    }

    @Test
    fun somethingAnotherBuildStoredIsNotGuessedAt() {
        assertNull(Failure.decode("app:SomeReasonFromANewerBuild"))
        assertNull(Failure.decode("quota"))
        assertNull(Failure.decode("http:"))
    }

    @Test
    fun everyAppFailureHasWords() {
        AppFailure.entries.forEach { reason ->
            val words = reason.toUiText().english()
            assertTrue("$reason has no words", words.isNotBlank())
            assertFalse("$reason shows its name", words.contains(reason.name))
        }
    }

    @Test
    fun anExceptionIsShownAsTheAppsOwnReasonWhenItHasOne() {
        assertEquals(Failure.App(AppFailure.AccountChanged), ApiResult.Unexpected(AccountChangedException()).failure())
        assertEquals(Failure.App(AppFailure.UnsafePath), appFailure(AppFailure.UnsafePath).failure())
        assertEquals(Failure.Unexpected("kaboom"), ApiResult.Unexpected(IllegalStateException("kaboom")).failure())
    }

    @Test
    fun aForbiddenAnswerSaysWhyWithoutClaimingWhich() {
        assertTrue(
            Failure
                .Http(403)
                .toUiText()
                .english()
                .contains("may not have permission"),
        )
        assertEquals("Server returned 500", Failure.Http(500).toUiText().english())
    }
}
