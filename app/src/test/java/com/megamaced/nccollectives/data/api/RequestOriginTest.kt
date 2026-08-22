package com.megamaced.nccollectives.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [HostInterceptor.originOf] — S-23.
 *
 * Only two URL shapes are ones this app built: Retrofit's placeholder base
 * and a URL already built from the stored host. Anything else arrived as
 * content (an image ref in a shared page body, say) and gets no
 * [RequestOrigin], which is what stops [AuthInterceptor] attaching
 * Basic-auth to it — and what stops [HostInterceptor] re-pointing the
 * attacker's path and query at the victim's own server.
 */
class RequestOriginTest {
    private val target = "https://cloud.example.com/nextcloud".toHttpUrl()

    private fun originOf(original: String): RequestOrigin? = HostInterceptor.originOf(original.toHttpUrl(), target)

    @Test
    fun retrofitPlaceholderUrl_isTheAppsOwnBaseUrl() {
        assertEquals(
            RequestOrigin.AppBaseUrl,
            originOf("https://placeholder.invalid/ocs/v2.php/apps/collectives/api/v1.0/collectives"),
        )
    }

    @Test
    fun urlBuiltFromStoredHost_isStoredHost() {
        assertEquals(
            RequestOrigin.StoredHost,
            originOf("https://cloud.example.com/nextcloud/remote.php/dav/files/bob/Page.md"),
        )
    }

    @Test
    fun storedHostComparisonIsCaseInsensitive() {
        assertEquals(RequestOrigin.StoredHost, originOf("https://Cloud.Example.COM/nextcloud/x"))
    }

    @Test
    fun foreignHost_hasNoOrigin() {
        assertNull(originOf("https://evil.example.org/index.php/apps/settings/x?confirm=1"))
    }

    @Test
    fun subdomainOfStoredHost_hasNoOrigin() {
        // Nothing in the app builds a URL on a sub-host, so it can only have
        // come from content.
        assertNull(originOf("https://files.cloud.example.com/x.png"))
    }

    @Test
    fun placeholderSubdomain_hasNoOrigin() {
        assertNull(originOf("https://evil.placeholder.invalid/x"))
    }

    @Test
    fun placeholderHostMatchesTheBaseUrlNetworkModuleUses() {
        // The two must agree or every API request looks foreign.
        assertEquals("placeholder.invalid", HostInterceptor.PLACEHOLDER_HOST)
    }
}
