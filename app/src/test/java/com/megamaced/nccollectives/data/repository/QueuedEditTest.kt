package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #18. The bug was that a second offline save replaced the first
 * queue row and nothing carried the first edit forward. The reader-side
 * half of the fix (the queued body overlaid on the page row) needs Room to
 * exercise; what is decidable in isolation is the metadata the replacement
 * has to preserve, which is what these pin.
 */
class CoalesceQueuedEditTest {
    private fun queued(
        baseEtag: String? = "etag-1",
        body: String = "base + A",
        queuedAt: Long = 1_000L,
        status: String = "PENDING",
        forceWrite: Boolean = false,
    ) = EditQueueEntity(
        pageId = 7L,
        baseEtag = baseEtag,
        newBodyMd = body,
        queuedAt = queuedAt,
        status = status,
        forceWrite = forceWrite,
    )

    @Test
    fun `a first offline save bases itself on the page's server etag`() {
        val row = coalesceQueuedEdit(
            existing = null,
            pageId = 7L,
            serverEtag = "etag-1",
            newBody = "base + A",
            now = 1_000L,
        )
        assertEquals("etag-1", row.baseEtag)
        assertEquals("base + A", row.newBodyMd)
        assertEquals(1_000L, row.queuedAt)
        assertEquals("PENDING", row.status)
        assertFalse(row.forceWrite)
    }

    @Test
    fun `a second save keeps the etag the chain started from`() {
        // Not `serverEtag`: edit B was authored on top of edit A, which was
        // written against etag-1. Re-reading the page row here would be
        // reading a value the queued text has never been compared against.
        val row = coalesceQueuedEdit(
            existing = queued(baseEtag = "etag-1"),
            pageId = 7L,
            serverEtag = "etag-2",
            newBody = "base + A + B",
            now = 2_000L,
        )
        assertEquals("etag-1", row.baseEtag)
        assertEquals("base + A + B", row.newBodyMd)
    }

    @Test
    fun `a null etag on an existing row is not healed from the page row`() {
        // Null is meaningful — a force-write, or a server that sends no
        // ETag header. Replacing it with the page's etag would re-arm an
        // `If-Match` the user has already overridden.
        val row = coalesceQueuedEdit(
            existing = queued(baseEtag = null),
            pageId = 7L,
            serverEtag = "etag-2",
            newBody = "base + A + B",
            now = 2_000L,
        )
        assertNull(row.baseEtag)
    }

    @Test
    fun `force-write is sticky across later edits`() {
        // B-46: once the user has chosen "replace with my draft", dropping
        // the flag hands the next flush a precondition to fail and turns
        // their override back into a conflict.
        val row = coalesceQueuedEdit(
            existing = queued(forceWrite = true, baseEtag = null),
            pageId = 7L,
            serverEtag = "etag-2",
            newBody = "base + A + B",
            now = 2_000L,
        )
        assertTrue(row.forceWrite)
    }

    @Test
    fun `the queue time stays the earliest so one page cannot starve others`() {
        val row = coalesceQueuedEdit(
            existing = queued(queuedAt = 1_000L),
            pageId = 7L,
            serverEtag = "etag-1",
            newBody = "base + A + B",
            now = 9_000L,
        )
        assertEquals(1_000L, row.queuedAt)
    }

    @Test
    fun `an in-flight row is re-armed rather than left mid-attempt`() {
        val row = coalesceQueuedEdit(
            existing = queued(status = "IN_FLIGHT"),
            pageId = 7L,
            serverEtag = "etag-1",
            newBody = "base + A + B",
            now = 2_000L,
        )
        assertEquals("PENDING", row.status)
    }
}

/**
 * B-16, extracted from `appendToPage` so the rule is pinned rather than
 * only commented. A share into a page must start a new markdown block.
 */
class AppendedBodyTest {
    @Test
    fun `appending to an empty body is just the text`() {
        assertEquals("shared", appendedBody(base = "", text = "shared"))
    }

    @Test
    fun `a body with no trailing newline gains a blank line`() {
        // The case B-16 was about: `# Heading` + one newline parses the
        // shared text *inside* the heading.
        assertEquals("# Heading\n\nshared", appendedBody(base = "# Heading", text = "shared"))
    }

    @Test
    fun `a body ending in one newline gains the second`() {
        assertEquals("# Heading\n\nshared", appendedBody(base = "# Heading\n", text = "shared"))
    }

    @Test
    fun `a body already ending in a blank line gains nothing`() {
        assertEquals("# Heading\n\nshared", appendedBody(base = "# Heading\n\n", text = "shared"))
    }

    @Test
    fun `repeated appends stay separate blocks`() {
        val once = appendedBody(base = "base", text = "A")
        assertEquals("base\n\nA\n\nB", appendedBody(base = once, text = "B"))
    }
}

/**
 * Issue #29 — the precondition a foreground save carries, and a regression
 * the reader-side overlay in #18 introduced.
 *
 * The losing sequence: fetch at ETag A, edit offline (queued with baseEtag
 * A), another client moves the server to B, reopen the page so revalidation
 * advances the row to B, press Save. Taking the precondition off the page row
 * sent the queued body with `If-Match: B`, which succeeds and discards the
 * other client's edit.
 */
class SavePreconditionTest {
    private fun queued(
        baseEtag: String? = "etag-A",
        status: String = "PENDING",
        forceWrite: Boolean = false,
    ) = EditQueueEntity(
        pageId = 7L,
        baseEtag = baseEtag,
        newBodyMd = "base + A",
        queuedAt = 1_000L,
        status = status,
        forceWrite = forceWrite,
    )

    @Test
    fun `with no queued edit the page row's etag is the precondition`() {
        val precondition = savePrecondition(queued = null, pageEtag = "etag-B")
        assertEquals("etag-B", precondition.baseEtag)
        assertFalse(precondition.forceWrite)
    }

    @Test
    fun `a queued edit's own base etag wins over the refreshed page row`() {
        // The whole of issue #29: etag-B is what the server has now, and
        // etag-A is what the queued text was written against.
        val precondition = savePrecondition(queued = queued(baseEtag = "etag-A"), pageEtag = "etag-B")
        assertEquals("etag-A", precondition.baseEtag)
    }

    @Test
    fun `an in-flight queued edit is treated the same as a pending one`() {
        val precondition = savePrecondition(queued = queued(status = "IN_FLIGHT"), pageEtag = "etag-B")
        assertEquals("etag-A", precondition.baseEtag)
    }

    @Test
    fun `a force-write queued edit carries no precondition`() {
        // B-46: the user has already chosen to override the server. Re-arming
        // an `If-Match` here would turn their override back into a conflict.
        val precondition = savePrecondition(queued = queued(forceWrite = true, baseEtag = null), pageEtag = "etag-B")
        assertNull(precondition.baseEtag)
        assertTrue(precondition.forceWrite)
    }

    @Test
    fun `a null base etag on a queued edit is not healed from the page row`() {
        // An ETag-less server. Healing it would claim a precondition the
        // queued text was never compared against.
        val precondition = savePrecondition(queued = queued(baseEtag = null), pageEtag = "etag-B")
        assertNull(precondition.baseEtag)
    }

    @Test
    fun `a conflicted row leaves the page row's etag in charge`() {
        // Its draft is already on the page row beside the server's body, the
        // user has been shown both, and the page's etag is the version they
        // were shown.
        val precondition = savePrecondition(queued = queued(status = "CONFLICTED"), pageEtag = "etag-B")
        assertEquals("etag-B", precondition.baseEtag)
        assertFalse(precondition.forceWrite)
    }
}
