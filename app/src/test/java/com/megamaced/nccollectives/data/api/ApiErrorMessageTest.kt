package com.megamaced.nccollectives.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-84: 403 is the common case for a members list, not an anomaly, and
 * "Server returned 403" told the user nothing. The wording must also stop
 * short of asserting non-membership — Circles answers 403 both for a team
 * the user isn't in and for a team that doesn't exist, and won't say which.
 */
class ApiErrorMessageTest {
    @Test
    fun `403 explains itself without claiming to know which cause it is`() {
        val message = ApiResult.HttpError(403, "Forbidden").userMessage()

        assertEquals(
            "Access refused (403). You may not have permission, or it may no longer exist.",
            message,
        )
        // The ambiguity is the point: never assert non-membership.
        assertTrue(message!!.contains("may not"))
    }

    @Test
    fun `other statuses keep the generic wording`() {
        assertEquals("Server returned 500", ApiResult.HttpError(500, "Server Error").userMessage())
        assertEquals("Server returned 404", ApiResult.HttpError(404, "Not Found").userMessage())
    }

    @Test
    fun `success has no message`() {
        assertNull(ApiResult.Success(Unit).userMessage())
    }
}
