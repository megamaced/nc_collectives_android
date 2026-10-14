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
    fun `a pre-release suffix is kept as dot-separated identifiers`() {
        assertEquals(SemVer(2, 10, 0, listOf("rc1")), parseSemVer("2.10.0-rc1"))
        assertEquals(SemVer(3, 0, 0, listOf("beta", "2")), parseSemVer("v3.0.0-beta.2"))
    }

    @Test
    fun `hyphens inside a pre-release identifier are allowed`() {
        assertEquals(SemVer(3, 0, 0, listOf("beta-x", "1")), parseSemVer("3.0.0-beta-x.1"))
    }

    @Test
    fun `build metadata is dropped`() {
        assertEquals(SemVer(2, 10, 0), parseSemVer("2.10.0+build.7"))
        assertEquals(SemVer(2, 10, 0), parseSemVer("2.10.0+build-7"))
        assertEquals(SemVer(3, 0, 0, listOf("beta", "1")), parseSemVer("3.0.0-beta.1+debug"))
    }

    @Test
    fun `an empty pre-release or identifier is rejected`() {
        assertNull(parseSemVer("3.0.0-"))
        assertNull(parseSemVer("3.0.0-beta..1"))
        assertNull(parseSemVer("3.0.0-beta."))
    }

    @Test
    fun `a numeric pre-release identifier with a leading zero is rejected`() {
        assertNull(parseSemVer("3.0.0-beta.01"))
        assertNull(parseSemVer("3.0.0-00"))
    }

    @Test
    fun `zero itself and alphanumerics starting with zero are allowed`() {
        assertEquals(SemVer(3, 0, 0, listOf("beta", "0")), parseSemVer("3.0.0-beta.0"))
        assertEquals(SemVer(3, 0, 0, listOf("01a")), parseSemVer("3.0.0-01a"))
    }

    @Test
    fun `malformed build metadata is rejected`() {
        assertNull(parseSemVer("3.0.0+"))
        assertNull(parseSemVer("3.0.0+build..7"))
        assertNull(parseSemVer("3.0.0+a+b"))
        assertNull(parseSemVer("3.0.0-beta.1+"))
    }

    @Test
    fun `build metadata may have leading zeros`() {
        assertEquals(SemVer(3, 0, 0), parseSemVer("3.0.0+007"))
    }

    @Test
    fun `a pre-release identifier outside the allowed characters is rejected`() {
        assertNull(parseSemVer("3.0.0-beta_1"))
        assertNull(parseSemVer("3.0.0-bêta"))
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

    @Test
    fun `a release outranks its own pre-releases`() {
        // The beta tester's case: running 3.0.0-beta.N, final 3.0.0 must be newer.
        assertTrue(parseSemVer("3.0.0-beta.1")!! < parseSemVer("3.0.0")!!)
        assertTrue(parseSemVer("3.0.0-beta.9")!! < parseSemVer("v3.0.0")!!)
        assertTrue(parseSemVer("3.0.0")!! > parseSemVer("3.0.0-rc.1")!!)
    }

    @Test
    fun `a pre-release outranks the previous release`() {
        assertTrue(parseSemVer("3.0.0-beta.1")!! > parseSemVer("2.13.2")!!)
    }

    @Test
    fun `numeric pre-release identifiers order by value`() {
        assertTrue(parseSemVer("3.0.0-beta.2")!! < parseSemVer("3.0.0-beta.10")!!)
        assertTrue(parseSemVer("3.0.0-beta.1")!! < parseSemVer("3.0.0-beta.2")!!)
        assertTrue(parseSemVer("3.0.0-1")!! < parseSemVer("3.0.0-99999999999999999999")!!)
    }

    @Test
    fun `alphanumeric pre-release identifiers order in ASCII order`() {
        assertTrue(parseSemVer("3.0.0-alpha")!! < parseSemVer("3.0.0-beta")!!)
        assertTrue(parseSemVer("3.0.0-beta.1")!! < parseSemVer("3.0.0-rc.1")!!)
    }

    @Test
    fun `numeric identifiers sort below alphanumeric ones`() {
        assertTrue(parseSemVer("3.0.0-1")!! < parseSemVer("3.0.0-alpha")!!)
        assertTrue(parseSemVer("3.0.0-alpha.1")!! < parseSemVer("3.0.0-alpha.beta")!!)
    }

    @Test
    fun `a longer pre-release outranks its own prefix`() {
        assertTrue(parseSemVer("3.0.0-alpha")!! < parseSemVer("3.0.0-alpha.1")!!)
    }

    @Test
    fun `the SemVer specification's example chain is in ascending order`() {
        val chain =
            listOf(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0",
            ).map { parseSemVer(it)!! }
        chain.zipWithNext().forEach { (lower, higher) -> assertTrue("$lower < $higher", lower < higher) }
    }

    @Test
    fun `build metadata takes no part in precedence`() {
        assertEquals(0, parseSemVer("3.0.0-beta.1+debug")!!.compareTo(parseSemVer("3.0.0-beta.1")!!))
        assertEquals(0, parseSemVer("2.13.2+debug")!!.compareTo(parseSemVer("2.13.2")!!))
    }
}
