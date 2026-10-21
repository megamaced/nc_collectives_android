package com.megamaced.nccollectives.ui.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Pins [onFailureMessage] — the one place a repository result turns into
 * something a screen shows.
 *
 * The load-bearing case is [ApiResult.Success]: the whole point of the
 * helper is that a successful call reports *nothing*, so it can't blank a
 * message another action has just set. The rest pin that every failure arm
 * really does produce a message, because "null means success" is only safe
 * while that holds.
 */
@RunWith(AndroidJUnit4::class)
class ScreenDefaultsTest {
    private fun messageFrom(result: ApiResult<*>): String? {
        var seen: UiText? = null
        var calls = 0
        result.onFailureMessage {
            seen = it
            calls++
        }
        assertTrue("called at most once, got $calls", calls <= 1)
        return seen?.english()
    }

    @Test
    fun success_reportsNothing() {
        assertEquals(null, messageFrom(ApiResult.Success(listOf("page"))))
    }

    @Test
    fun successOfUnit_reportsNothing() {
        // The shape every restore/purge/toggle returns.
        assertEquals(null, messageFrom(ApiResult.Success(Unit)))
    }

    @Test
    fun networkError_reportsConnectionMessage() {
        assertEquals(
            "Couldn't reach the server. Check your connection.",
            messageFrom(ApiResult.NetworkError(IOException("no route to host"))),
        )
    }

    @Test
    fun httpError_reportsStatusCode() {
        assertEquals("Server returned 500", messageFrom(ApiResult.HttpError(500, "Server Error")))
    }

    @Test
    fun unauthorised_reportsSessionExpired() {
        assertEquals("Session expired. Please sign in again.", messageFrom(ApiResult.Unauthorised))
    }

    @Test
    fun conflict_reportsServerSideChange() {
        assertEquals(
            "Page changed on the server while you were editing.",
            messageFrom(ApiResult.Conflict),
        )
    }

    @Test
    fun unexpected_reportsCauseMessage() {
        assertEquals("Something went wrong: kaboom", messageFrom(ApiResult.Unexpected(IllegalStateException("kaboom"))))
    }

    @Test
    fun unexpectedWithoutMessage_stillReportsSomething() {
        // A message-less throwable must not read as success.
        assertEquals("Something went wrong.", messageFrom(ApiResult.Unexpected(IllegalStateException())))
    }
}
