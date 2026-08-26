package com.megamaced.nccollectives.ui.screen

import com.megamaced.nccollectives.data.api.ApiResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins [isRetryableFailure], the single gate between a failed read and a
 * Retry button.
 *
 * This replaced two divergent copies — the members strip called every
 * `HttpError` terminal, the members screen called only 403 terminal — which
 * meant the same failure offered Retry on one surface and not the other. The
 * cases below are the taxonomy `EditFlushWorker.flushFailureAction` already
 * applies to the sync path, so a status is classified the same way wherever
 * it lands.
 */
class RetryPolicyTest {
    @Test
    fun `403 is terminal — it is the expected answer for a non-member, and retrying throttles the caller`() {
        assertFalse(isRetryableFailure(ApiResult.HttpError(403, "Forbidden")))
    }

    @Test
    fun `the rest of 4xx is terminal — repeating a rejected request cannot fix it`() {
        assertFalse(isRetryableFailure(ApiResult.HttpError(400, "Bad Request")))
        assertFalse(isRetryableFailure(ApiResult.HttpError(404, "Not Found")))
        assertFalse(isRetryableFailure(ApiResult.HttpError(422, "Unprocessable")))
    }

    @Test
    fun `408 and 429 are retryable — the server is explicitly saying ask again`() {
        assertTrue(isRetryableFailure(ApiResult.HttpError(408, "Request Timeout")))
        assertTrue(isRetryableFailure(ApiResult.HttpError(429, "Too Many Requests")))
    }

    @Test
    fun `5xx is retryable because it is transient`() {
        assertTrue(isRetryableFailure(ApiResult.HttpError(500, "Internal Server Error")))
        assertTrue(isRetryableFailure(ApiResult.HttpError(502, "Bad Gateway")))
        assertTrue(isRetryableFailure(ApiResult.HttpError(503, "Service Unavailable")))
    }

    @Test
    fun `507 is terminal — the server needs to change before anything else will`() {
        assertFalse(isRetryableFailure(ApiResult.HttpError(507, "Insufficient Storage")))
    }

    @Test
    fun `a request that never reached the server is retryable`() {
        assertTrue(isRetryableFailure(ApiResult.NetworkError(IOException("no route to host"))))
    }

    @Test
    fun `session and conflict arms are terminal`() {
        assertFalse(isRetryableFailure(ApiResult.Unauthorised))
        assertFalse(isRetryableFailure(ApiResult.Conflict))
        assertFalse(isRetryableFailure(ApiResult.Unexpected(IllegalStateException("boom"))))
    }

    @Test
    fun `success offers no retry — there is nothing to retry`() {
        assertFalse(isRetryableFailure(ApiResult.Success(emptyList<String>())))
    }
}
