package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldKeepInWebView] — the navigation gate behind
 * [StripChromeWebViewClient.shouldOverrideUrlLoading].
 *
 * Pins the link-routing contract adopted from nextcloud/notes-android
 * (`NoteDirectEditFragment`, commit 398abd51): only same-host `https`
 * navigations stay in the editor WebView; everything else leaves, so an
 * in-editor link (mention, file ref, `mailto:`, external URL) can't
 * hijack the edit session. Fails closed when either host is missing.
 */
class PageEditWebNavigationTest {
    private val host = "cloud.example.com"

    @Test
    fun sameHostHttps_staysInWebView() {
        assertTrue(shouldKeepInWebView(host, "https", host))
    }

    @Test
    fun sameHostHttps_isCaseInsensitive() {
        assertTrue(shouldKeepInWebView("Cloud.Example.Com", "HTTPS", host))
    }

    @Test
    fun differentHost_leavesWebView() {
        assertFalse(shouldKeepInWebView("evil.example.org", "https", host))
    }

    @Test
    fun sameHostHttp_leavesWebView() {
        // Non-https even on the right host is routed out — the app is
        // HTTPS-only (network_security_config cleartext=false) and the
        // editor session URL is always https.
        assertFalse(shouldKeepInWebView(host, "http", host))
    }

    @Test
    fun mailtoScheme_leavesWebView() {
        // `mailto:` (and tel:/geo:/intent:) parse with a null host.
        assertFalse(shouldKeepInWebView(null, "mailto", host))
    }

    @Test
    fun nullAllowedHost_failsClosed() {
        // Couldn't parse a host from the session URL — never keep a
        // navigation in the WebView rather than risk hijacking it.
        assertFalse(shouldKeepInWebView(host, "https", null))
    }

    @Test
    fun emptyAllowedHost_failsClosed() {
        assertFalse(shouldKeepInWebView(host, "https", ""))
    }
}
