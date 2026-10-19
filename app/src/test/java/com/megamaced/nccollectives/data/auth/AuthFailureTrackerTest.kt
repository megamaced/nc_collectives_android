package com.megamaced.nccollectives.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 401 policy, three findings deep: B-2 gave it a threshold, B-51 made
 * any non-401 reset it, and issue #19 made the dispatch happen once per
 * streak — because expiry now removes an account and wipes the device
 * rather than flipping a flag.
 */
class AuthFailureTrackerTest {
    @Test
    fun `a single 401 does not expire the session`() {
        // B-2: a transient proxy 401, or a 401 from some non-Collectives
        // resource, used to sign the user out and lose in-flight saves.
        assertFalse(AuthFailureTracker().onResponse(401, G))
    }

    @Test
    fun `two consecutive 401s expire the session`() {
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G))
    }

    @Test
    fun `a success between two 401s breaks the streak`() {
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertFalse(tracker.onResponse(200, G))
        assertFalse(tracker.onResponse(401, G))
    }

    @Test
    fun `a 5xx between two 401s also breaks the streak`() {
        // B-51: the reset used to be `code in 200..299`, so a flaky reverse
        // proxy answering 401 -> 500 -> 401 signed the user out. A 5xx is no
        // evidence of a working auth exchange, but none of a dead token
        // either.
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertFalse(tracker.onResponse(503, G))
        assertFalse(tracker.onResponse(401, G))
    }

    @Test
    fun `a streak expires the session only once`() {
        // Issue #19: expiry removes an account and wipes local data now, so
        // every 401 after the threshold must not dispatch another one.
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G))
        assertFalse(tracker.onResponse(401, G))
        assertFalse(tracker.onResponse(401, G))
    }

    @Test
    fun `a working request re-arms expiry for a later streak`() {
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G))
        assertFalse(tracker.onResponse(200, G))
        assertFalse(tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G))
    }

    @Test
    fun `reset forgets a streak that had not yet expired`() {
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        tracker.reset()
        assertFalse(tracker.onResponse(401, G))
    }

    @Test
    fun `reset re-arms expiry after one has been dispatched`() {
        // What `endAccountSwitch` and `onLoginSuccess` rely on: a fresh
        // credential is live, so the next streak is a new question.
        val tracker = AuthFailureTracker()
        tracker.onResponse(401, G)
        assertTrue(tracker.onResponse(401, G))
        tracker.reset()
        assertFalse(tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G))
    }

    @Test
    fun `a run belongs to its generation, and an older one cannot add to a newer`() {
        // Issue #58: a late answer for the outgoing account counts toward its
        // own run, not the next account's.
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G))
        assertFalse("the next account's first 401 starts its own run", tracker.onResponse(401, G + 2))
        assertFalse("a late one from the old account adds nothing to it", tracker.onResponse(401, G))
        assertTrue(tracker.onResponse(401, G + 2))
    }

    @Test
    fun `an older generation's success does not break a newer run`() {
        val tracker = AuthFailureTracker()
        assertFalse(tracker.onResponse(401, G + 2))
        assertFalse(tracker.onResponse(200, G))
        assertTrue(tracker.onResponse(401, G + 2))
    }

    private companion object {
        const val G = 0L
    }
}
