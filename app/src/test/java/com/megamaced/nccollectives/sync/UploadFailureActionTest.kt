package com.megamaced.nccollectives.sync

import com.megamaced.nccollectives.data.api.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Issue #23. Every HTTP status, every `Conflict` and every unexpected
 * exception used to mark the row `FAILED` — a status the worker never
 * selects again and nothing moved a row out of — so a 503 behind a reverse
 * proxy lost the upload as surely as a 403 did.
 */
class UploadFailureActionTest {
    private fun action(
        result: ApiResult<*>,
        attempt: Int = 0,
    ) = uploadFailureAction(result, attempt)

    @Test
    fun `a dropped network waits for another run`() {
        assertEquals(UploadFailureAction.RetryLater, action(ApiResult.NetworkError(IOException("offline"))))
    }

    @Test
    fun `a request timeout waits`() {
        assertEquals(
            UploadFailureAction.RetryLater,
            action(ApiResult.HttpError(code = 408, message = "Request Timeout")),
        )
    }

    @Test
    fun `rate limiting waits`() {
        assertEquals(
            UploadFailureAction.RetryLater,
            action(ApiResult.HttpError(code = 429, message = "Too Many Requests")),
        )
    }

    @Test
    fun `a server error waits`() {
        assertEquals(
            UploadFailureAction.RetryLater,
            action(ApiResult.HttpError(code = 503, message = "Service Unavailable")),
        )
    }

    @Test
    fun `a forbidden upload is terminal`() {
        // Write access was revoked. Repeating the same PUT cannot fix it.
        assertEquals(
            UploadFailureAction.Terminal,
            action(ApiResult.HttpError(code = 403, message = "Forbidden")),
        )
    }

    @Test
    fun `a full quota is terminal`() {
        // 507: the *server* has to change before anything else will.
        assertEquals(
            UploadFailureAction.Terminal,
            action(ApiResult.HttpError(code = 507, message = "Insufficient Storage")),
        )
    }

    @Test
    fun `a precondition failure is terminal`() {
        assertEquals(UploadFailureAction.Terminal, action(ApiResult.Conflict))
    }

    @Test
    fun `an unexpected failure is terminal`() {
        assertEquals(
            UploadFailureAction.Terminal,
            action(ApiResult.Unexpected(IllegalStateException("no webdav url"))),
        )
    }

    @Test
    fun `the attempt cap wins over a retryable status`() {
        // WorkManager applies no cap of its own, so without this a
        // retryable arm would retry on exponential backoff for as long as
        // the app stayed installed -- invisibly, since only a settled row
        // puts anything on screen.
        assertEquals(
            UploadFailureAction.Terminal,
            action(ApiResult.NetworkError(IOException("offline")), attempt = MAX_UPLOAD_ATTEMPTS),
        )
    }

    @Test
    fun `one attempt short of the cap still waits`() {
        assertEquals(
            UploadFailureAction.RetryLater,
            action(ApiResult.NetworkError(IOException("offline")), attempt = MAX_UPLOAD_ATTEMPTS - 1),
        )
    }
}
