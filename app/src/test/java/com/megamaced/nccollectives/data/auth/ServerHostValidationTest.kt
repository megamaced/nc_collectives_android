package com.megamaced.nccollectives.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [serverHostOf], [hostMatches] and [isSameServerHttpsUrl] —
 * the one place the app decides whether a server-supplied URL belongs to
 * the Nextcloud the user signed in to.
 *
 * The comparison started life private to [NextcloudLoginFlow] guarding the
 * poll response's `server` field (S-17); it now also guards the login page
 * and poll endpoint from `login/v2` (S-26) and the `directEditing/open`
 * session URL loaded into the editor WebView (S-22), so these cases pin the
 * rule all three share.
 */
class ServerHostValidationTest {
    private val expected = "https://cloud.example.com"

    @Test
    fun serverHostOf_fullUrlWithSubdirectory_returnsBareHost() {
        assertEquals("cloud.example.com", serverHostOf("https://cloud.example.com/nextcloud"))
    }

    @Test
    fun serverHostOf_bareHost_isReadAsHttps() {
        // The user's typed input reaches this before normalisation.
        assertEquals("cloud.example.com", serverHostOf("cloud.example.com"))
        assertEquals("cloud.example.com", serverHostOf("cloud.example.com/"))
    }

    @Test
    fun serverHostOf_emptyString_returnsNull() {
        assertNull(serverHostOf(""))
    }

    @Test
    fun hostMatches_exactHost_matches() {
        assertTrue(hostMatches("https://cloud.example.com/index.php/login/v2", expected))
    }

    @Test
    fun hostMatches_isCaseInsensitive() {
        assertTrue(hostMatches("https://Cloud.Example.COM/x", expected))
    }

    @Test
    fun hostMatches_subdomainOfExpected_matches() {
        // A load-balanced install can answer with a canonical sub-host.
        assertTrue(hostMatches("https://files.cloud.example.com/x", expected))
    }

    @Test
    fun hostMatches_differentHost_doesNotMatch() {
        assertFalse(hostMatches("https://evil.example.org/x", expected))
    }

    @Test
    fun hostMatches_suffixWithoutDotBoundary_doesNotMatch() {
        // `notcloud.example.com` ends with the expected host's text but is a
        // different domain — the leading dot in the suffix check is what
        // separates them.
        assertFalse(hostMatches("https://xcloud.example.com/x", "https://cloud.example.com"))
    }

    @Test
    fun hostMatches_expectedAsBareHost_stillCompares() {
        assertTrue(hostMatches("https://cloud.example.com/x", "cloud.example.com"))
    }

    @Test
    fun hostMatches_unparsableReturnedUrl_doesNotMatch() {
        assertFalse(hostMatches("cloud.example.com", expected))
        assertFalse(hostMatches("", expected))
    }

    @Test
    fun isSameServerHttpsUrl_httpsOnExpectedHost_isAccepted() {
        assertTrue(
            isSameServerHttpsUrl(
                "https://cloud.example.com/index.php/apps/text/session?token=abc",
                expected,
            ),
        )
    }

    @Test
    fun isSameServerHttpsUrl_httpDowngrade_isRejected() {
        assertFalse(isSameServerHttpsUrl("http://cloud.example.com/x", expected))
    }

    @Test
    fun isSameServerHttpsUrl_otherHost_isRejected() {
        assertFalse(isSameServerHttpsUrl("https://phish.example.org/login", expected))
    }

    @Test
    fun isSameServerHttpsUrl_nonHttpScheme_isRejected() {
        // A Custom Tab / WebView would resolve these through the system.
        assertFalse(isSameServerHttpsUrl("intent://cloud.example.com/#Intent;end", expected))
        assertFalse(isSameServerHttpsUrl("javascript:alert(1)", expected))
        assertFalse(isSameServerHttpsUrl("file:///data/data/app/x.html", expected))
        assertFalse(isSameServerHttpsUrl("", expected))
    }

    @Test
    fun isSameServerHttpsUrl_schemeRelativeUrl_isRejected() {
        assertFalse(isSameServerHttpsUrl("//cloud.example.com/x", expected))
    }
}
