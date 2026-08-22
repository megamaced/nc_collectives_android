package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [DirectEditingRepositoryImpl.validatedSessionUrl] — S-22.
 *
 * The URL under test is loaded into a chromeless, JS-enabled WebView with
 * the `DirectEditingMobileInterface` bridge bound, so a server that names
 * any host it likes gets a full-screen page with no address bar in the one
 * context where a Nextcloud login prompt looks plausible. Only `https` on
 * the host we hold credentials for may pass.
 */
class DirectEditingSessionUrlTest {
    private val host = "https://cloud.example.com"

    @Test
    fun sessionUrlOnStoredHost_isAccepted() {
        val url = "https://cloud.example.com/index.php/apps/text/session?token=abc123"
        assertEquals(ApiResult.Success(url), DirectEditingRepositoryImpl.validatedSessionUrl(url, host))
    }

    @Test
    fun sessionUrlOnStoredHostWithSubdirectory_isAccepted() {
        // The stored host may carry a subdirectory prefix (B-12); the check
        // is on the host, not the path.
        val url = "https://cloud.example.com/nextcloud/index.php/apps/text/session?token=abc"
        assertEquals(
            ApiResult.Success(url),
            DirectEditingRepositoryImpl.validatedSessionUrl(url, "https://cloud.example.com/nextcloud"),
        )
    }

    @Test
    fun sessionUrlOnForeignHost_isRefused() {
        assertRefused("https://phish.example.org/index.php/login")
    }

    @Test
    fun sessionUrlOverHttp_isRefused() {
        assertRefused("http://cloud.example.com/index.php/apps/text/session?token=abc")
    }

    @Test
    fun sessionUrlWithNonHttpScheme_isRefused() {
        assertRefused("javascript:alert(document.cookie)")
        assertRefused("file:///data/data/com.megamaced.nccollectives/x.html")
        assertRefused("intent://cloud.example.com/#Intent;end")
    }

    @Test
    fun relativeOrEmptySessionUrl_isRefused() {
        // The DTO defaults `url` to "" when the field is absent.
        assertRefused("")
        assertRefused("/index.php/apps/text/session?token=abc")
    }

    private fun assertRefused(url: String) {
        val result = DirectEditingRepositoryImpl.validatedSessionUrl(url, host)
        assertTrue("expected refusal for $url but got $result", result is ApiResult.Unexpected)
    }
}
