package com.megamaced.nccollectives.sync

import com.megamaced.nccollectives.data.api.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Pins B-62. Both of `EditFlushWorker`'s failure arms used to park the row
 * back at `PENDING` and ask WorkManager to retry, with no attempt cap
 * anywhere — so a page deleted server-side, or an account whose edit rights
 * were revoked, retried the same doomed PUT every few hours indefinitely,
 * and did it invisibly: nothing but a `CONFLICTED` row reaches the UI.
 *
 * The asymmetry between 4xx and 5xx is the part worth pinning: a status the
 * same request will keep earning is terminal, a server-side blip isn't.
 */
class FlushFailureActionTest {
    @Test
    fun pageDeletedOrForbidden_isTerminal() {
        // The two that motivated the fix: the page is gone, or we may no
        // longer write it. Retrying is pure battery.
        assertEquals(FlushFailureAction.Terminal, flushFailureAction(404, runAttemptCount = 0))
        assertEquals(FlushFailureAction.Terminal, flushFailureAction(403, runAttemptCount = 0))
        assertEquals(FlushFailureAction.Terminal, flushFailureAction(423, runAttemptCount = 0))
        assertEquals(FlushFailureAction.Terminal, flushFailureAction(507, runAttemptCount = 0))
    }

    @Test
    fun timeoutAndRateLimit_retryDespiteBeing4xx() {
        // 408 and 429 are 4xx by number and "come back later" by meaning.
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(408, runAttemptCount = 0))
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(429, runAttemptCount = 0))
    }

    @Test
    fun serverError_retries() {
        // Diverges from `SyncWorker.retryDecision` on purpose: a sync GET has
        // nothing to lose by giving up, but the queue row is the user's only
        // copy of their edit, so a proxy hiccup is worth waiting out.
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(500, runAttemptCount = 0))
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(502, runAttemptCount = 0))
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(503, runAttemptCount = 0))
    }

    @Test
    fun noHttpStatus_retries() {
        // A dropped connection or an unclassifiable exception says nothing
        // about whether the write can ever land.
        assertEquals(FlushFailureAction.RetryLater, flushFailureAction(null, runAttemptCount = 0))
    }

    @Test
    fun attemptCap_makesEveryArmTerminal() {
        // The backstop: without it the statusless arms above have no bound at
        // all, and WorkManager supplies none of its own.
        assertEquals(
            FlushFailureAction.Terminal,
            flushFailureAction(null, runAttemptCount = MAX_FLUSH_ATTEMPTS),
        )
        assertEquals(
            FlushFailureAction.Terminal,
            flushFailureAction(503, runAttemptCount = MAX_FLUSH_ATTEMPTS + 5),
        )
        assertEquals(
            FlushFailureAction.RetryLater,
            flushFailureAction(503, runAttemptCount = MAX_FLUSH_ATTEMPTS - 1),
        )
    }

    @Test
    fun httpStatusOf_readsTheStatusOrAdmitsThereIsNone() {
        assertEquals(404, httpStatusOf(ApiResult.HttpError(404, "Not Found")))
        // `webDavCall` folds 412 into its own arm; the classifier still needs
        // to see it as the precondition failure it is.
        assertEquals(412, httpStatusOf(ApiResult.Conflict))
        assertNull(httpStatusOf(ApiResult.NetworkError(IOException("closed"))))
        assertNull(httpStatusOf(ApiResult.Unexpected(IllegalStateException("no url"))))
        assertNull(httpStatusOf(ApiResult.Success("etag")))
    }
}
