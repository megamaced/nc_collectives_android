package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #26: `parseSemVer` used `mapNotNull { it.toIntOrNull() }`, which
 * *dropped* the components it couldn't parse rather than rejecting the
 * input — so `1.foo.3` parsed as `1.3.0` and `foo.2` as `2.0.0`, both
 * contradicting the function's own numeric contract and both comparing
 * wrongly against the installed version.
 */
class ParseSemVerTest {
    @Test
    fun `a plain triple parses`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("2.10.0"))
    }

    @Test
    fun `a leading v is stripped in either case`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("v2.10.0"))
        assertEquals(SemVer(2, 10, 0), parseSemVer("V2.10.0"))
    }

    @Test
    fun `missing trailing components default to zero`() {
        // How the project's own tags are sometimes written.
        assertEquals(SemVer(2, 11, 0), parseSemVer("2.11"))
        assertEquals(SemVer(3, 0, 0), parseSemVer("3"))
    }

    @Test
    fun `a prerelease suffix is dropped`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("2.10.0-rc1"))
    }

    @Test
    fun `build metadata is dropped`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("2.10.0+build.7"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("  v2.10.0  "))
    }

    @Test
    fun `a non-numeric middle component is rejected`() {
        // Previously parsed as 1.3.0.
        assertNull(parseSemVer("1.foo.3"))
    }

    @Test
    fun `a non-numeric leading component is rejected`() {
        // Previously parsed as 2.0.0.
        assertNull(parseSemVer("foo.2"))
    }

    @Test
    fun `an empty component is rejected`() {
        assertNull(parseSemVer("2..0"))
        assertNull(parseSemVer(""))
        assertNull(parseSemVer("2."))
    }

    @Test
    fun `more than three components is rejected`() {
        assertNull(parseSemVer("1.2.3.4"))
    }

    @Test
    fun `an overflowing component is rejected rather than truncated`() {
        assertNull(parseSemVer("99999999999.0.0"))
    }

    @Test
    fun `a negative component is rejected`() {
        assertNull(parseSemVer("1.-2.0"))
    }

    @Test
    fun `versions order numerically rather than lexically`() {
        // The comparison this exists for: 2.10.0 is newer than 2.9.0, which
        // string ordering gets backwards.
        val older = parseSemVer("2.9.0")!!
        val newer = parseSemVer("2.10.0")!!
        assertTrue(older < newer)
    }
}
