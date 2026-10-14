package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.db.entity.BODY_TIMESTAMP_UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the B-58 decision: opening a page always talks to the server when it
 * can. The bug being guarded against is the old `if (bodyMd == null)` gate,
 * under which a cached body was never re-checked and a page edited elsewhere
 * stayed frozen for the life of the install.
 */
class BodyFetchPlanTest {
    @Test
    fun cachedBodyWithEtag_revalidates() {
        // The regression case. A body being present is *not* a reason to skip
        // the server.
        val plan = bodyFetchPlan(bodyMd = "# Hello", bodyEtag = "abc123", bodyTimestamp = 1_700_000_000L)
        assertEquals(BodyFetchPlan.Revalidate("abc123"), plan)
    }

    @Test
    fun noCachedBody_fetchesWhole() {
        assertEquals(BodyFetchPlan.FetchWhole, bodyFetchPlan(bodyMd = null, bodyEtag = "abc123", bodyTimestamp = 1_700_000_000L))
    }

    @Test
    fun cachedBodyWithoutEtag_fetchesWhole() {
        // Nothing to put in `If-None-Match`, so the conditional request would
        // be a plain GET with extra steps.
        assertEquals(BodyFetchPlan.FetchWhole, bodyFetchPlan(bodyMd = "# Hello", bodyEtag = null, bodyTimestamp = 1_700_000_000L))
    }

    @Test
    fun emptyCachedBody_stillRevalidates() {
        // An empty page is a legitimate page, not a missing cache entry —
        // `bodyMd = ""` came from the server the same as any other body.
        assertEquals(BodyFetchPlan.Revalidate("abc123"), bodyFetchPlan(bodyMd = "", bodyEtag = "abc123", bodyTimestamp = 1_700_000_000L))
    }

    @Test
    fun nothingCached_fetchesWhole() {
        assertEquals(BodyFetchPlan.FetchWhole, bodyFetchPlan(bodyMd = null, bodyEtag = null, bodyTimestamp = null))
    }

    @Test
    fun cachedBodyOfUnknownVersion_fetchesWhole() {
        // From before the version was stored, or the user's own save: a 304
        // would say nothing about which version it is.
        assertEquals(BodyFetchPlan.FetchWhole, bodyFetchPlan(bodyMd = "# Hello", bodyEtag = "abc123", bodyTimestamp = null))
    }

    @Test
    fun serverThatSendsNoLastModified_stillRevalidates() {
        // Otherwise a server without `Last-Modified` would cost a whole
        // download on every open.
        assertEquals(
            BodyFetchPlan.Revalidate("abc123"),
            bodyFetchPlan(bodyMd = "# Hello", bodyEtag = "abc123", bodyTimestamp = BODY_TIMESTAMP_UNAVAILABLE),
        )
    }
}
