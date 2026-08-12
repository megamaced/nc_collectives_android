package com.megamaced.nccollectives.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins B-57. The failure this prevents is subtle and slow: a one-shot that
 * asks WorkManager to retry holds the unique work name through an
 * exponential backoff capped at five hours, and every foreground `syncNow()`
 * fired in that window is dropped — so the app quietly stops syncing after a
 * single bad network moment.
 */
class SyncRetryDecisionTest {
    @Test
    fun oneShotNetworkFailure_completesRatherThanRetrying() {
        assertEquals(
            SyncRetryDecision.Complete,
            retryDecision(SyncOutcome.Retryable("Couldn't reach the server"), isOneShot = true),
        )
    }

    @Test
    fun periodicNetworkFailure_retries() {
        // The periodic run has no successor coming, so backoff is what makes
        // it eventually land.
        assertEquals(
            SyncRetryDecision.Retry,
            retryDecision(SyncOutcome.Retryable("Couldn't reach the server"), isOneShot = false),
        )
    }

    @Test
    fun serverRefusal_neverRetries() {
        // Same request, same answer — retrying just burns battery.
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Failed("Server returned 500"), isOneShot = false))
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Failed("Server returned 500"), isOneShot = true))
    }

    @Test
    fun unauthorised_completes() {
        // SessionManager's 401 streak drives sign-out; the worker bails.
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Unauthorised, isOneShot = false))
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Unauthorised, isOneShot = true))
    }

    @Test
    fun success_completes() {
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Success, isOneShot = false))
        assertEquals(SyncRetryDecision.Complete, retryDecision(SyncOutcome.Success, isOneShot = true))
    }
}
