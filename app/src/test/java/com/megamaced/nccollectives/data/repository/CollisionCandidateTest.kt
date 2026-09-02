package com.megamaced.nccollectives.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/**
 * The UTF-8 byte cap on an attachment filename — issue #43.
 *
 * The old cap was `take(200)`, which counts UTF-16 `Char`s. Nextcloud's limit
 * is in bytes, so a name of multi-byte characters passed a check expressed in
 * the wrong unit and the upload then failed identically on every attempt,
 * with a retry that could not help because the name never changed. `take`
 * could also split a surrogate pair, producing a name whose encoding was
 * invalid before its length was even considered.
 */
class BoundedFileNameTest {
    @Test
    fun `an ordinary name is untouched`() {
        assertEquals("photo.png", boundedFileName("photo.png"))
    }

    @Test
    fun `a name exactly at the limit is untouched`() {
        val name = "a".repeat(MAX_ATTACHMENT_NAME_BYTES - 4) + ".png"
        assertEquals(MAX_ATTACHMENT_NAME_BYTES, name.utf8ByteLength())
        assertEquals(name, boundedFileName(name))
    }

    @Test
    fun `an over-long ASCII name is cut to the limit`() {
        val bounded = boundedFileName("a".repeat(400) + ".png")

        assertEquals(MAX_ATTACHMENT_NAME_BYTES, bounded.utf8ByteLength())
        assertTrue(bounded.endsWith(".png"))
    }

    @Test
    fun `emoji are measured in bytes, not UTF-16 units`() {
        // 100 emoji: 200 `Char`s, so the old `take(200)` let it straight
        // through — and roughly 400 UTF-8 bytes, twice the limit.
        val name = "😀".repeat(100) + ".png"
        assertEquals(200 + 4, name.length)

        val bounded = boundedFileName(name)

        assertTrue(
            "was ${bounded.utf8ByteLength()} bytes",
            bounded.utf8ByteLength() <= MAX_ATTACHMENT_NAME_BYTES,
        )
    }

    @Test
    fun `a cut never lands inside a surrogate pair`() {
        val bounded = boundedFileName("😀".repeat(100) + ".png")

        assertFalse(
            "a lone surrogate means the name is no longer valid text",
            bounded.any { it.isSurrogate() && !bounded.isPaired(bounded.indexOf(it)) },
        )
        // The direct statement of the same thing: encoding and decoding is
        // lossless, which a split pair would not be.
        assertEquals(bounded, String(bounded.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `a cut never lands inside a multi-byte character`() {
        // CJK: three bytes each, so a byte-offset cut has two chances in
        // three of landing mid-character.
        val bounded = boundedFileName("字".repeat(200) + ".png")

        assertEquals(bounded, String(bounded.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(bounded.utf8ByteLength() <= MAX_ATTACHMENT_NAME_BYTES)
        assertFalse("no replacement characters", bounded.contains('�'))
    }

    @Test
    fun `the extension survives truncation`() {
        // It is what the content type and the image-vs-file demotion are read
        // from, so the stem is what gives up room.
        assertTrue(boundedFileName("字".repeat(300) + ".jpeg").endsWith(".jpeg"))
        assertTrue(boundedFileName("a".repeat(300) + ".tar.gz").endsWith(".gz"))
    }

    @Test
    fun `a name that is nothing but an over-long extension still yields a usable name`() {
        val bounded = boundedFileName("." + "x".repeat(500))

        assertTrue(bounded.isNotEmpty())
        assertTrue(bounded.utf8ByteLength() <= MAX_ATTACHMENT_NAME_BYTES)
        assertFalse("S-5: never a leading-dot name", bounded.startsWith('.'))
    }

    @Test
    fun `a collision suffix is fitted inside the same budget, not appended past it`() {
        // The suffix used to be added after the cap had already been applied,
        // so every rename pushed the name further over the limit.
        val name = boundedFileName("a".repeat(400) + ".png")

        for (counter in listOf(1, 9, 10, 99, 100, 1000)) {
            val candidate = collisionCandidate(name, counter)
            assertTrue(
                "candidate $counter was ${candidate.utf8ByteLength()} bytes",
                candidate.utf8ByteLength() <= MAX_ATTACHMENT_NAME_BYTES,
            )
            assertTrue(candidate.endsWith("-$counter.png"))
        }
    }

    @Test
    fun `a collision candidate for a multi-byte name also stays inside the budget`() {
        val name = boundedFileName("字".repeat(300) + ".png")

        val candidate = collisionCandidate(name, 12)

        assertTrue(candidate.utf8ByteLength() <= MAX_ATTACHMENT_NAME_BYTES)
        assertEquals(candidate, String(candidate.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(candidate.endsWith("-12.png"))
    }

    /** Whether the character at [index] is half of a well-formed pair. */
    private fun String.isPaired(index: Int): Boolean =
        (index + 1 < length && Character.isSurrogatePair(this[index], this[index + 1])) ||
            (index > 0 && Character.isSurrogatePair(this[index - 1], this[index]))
}
