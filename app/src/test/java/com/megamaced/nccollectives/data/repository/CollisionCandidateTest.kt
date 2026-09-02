package com.megamaced.nccollectives.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The candidate sequence behind `resolveCollisionFreeName`, and the sequence
 * `renameForRemoteCollision` walks after the server refuses a create with
 * `If-None-Match: *` (issue #24).
 */
class CollisionCandidateTest {
    @Test
    fun `the first candidate is the name itself`() {
        assertEquals("photo.png", collisionCandidate("photo.png", 0))
    }

    @Test
    fun `the suffix goes before the extension`() {
        // So a renamed file keeps the extension the content type and the
        // image-vs-file decision are read from.
        assertEquals("photo-1.png", collisionCandidate("photo.png", 1))
        assertEquals("photo-2.png", collisionCandidate("photo.png", 2))
    }

    @Test
    fun `a name with no extension is suffixed directly`() {
        assertEquals("README-1", collisionCandidate("README", 1))
    }

    @Test
    fun `only the last dot separates the extension`() {
        // Imperfect for double extensions, and the same answer every other
        // part of the app gets from substringAfterLast('.').
        assertEquals("photo.tar-1.gz", collisionCandidate("photo.tar.gz", 1))
    }

    @Test
    fun `candidates are distinct so the search terminates`() {
        val candidates = (0..5).map { collisionCandidate("photo.png", it) }
        assertEquals(candidates.size, candidates.toSet().size)
    }
}
