package com.megamaced.nccollectives.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.Failure
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** What a pull to refresh or "Sync now" tells the user about how the sync went. */
@RunWith(AndroidJUnit4::class)
class SyncOutcomeTextTest {
    @Test
    fun aCleanSyncSaysNothing() {
        assertNull(SyncOutcome.Success.failureText())
    }

    @Test
    fun aFailedSyncSaysWhy() {
        assertEquals("Session expired. Please sign in again.", SyncOutcome.Unauthorised.failureText()?.english())
        assertEquals("Couldn't reach the server. Check your connection.", SyncOutcome.Retryable(Failure.Network).failureText()?.english())
        assertEquals("Server returned 502", SyncOutcome.Failed(Failure.Http(502)).failureText()?.english())
    }
}
