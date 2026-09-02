package com.megamaced.nccollectives.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [HostInterceptor.originOf] — S-23, and issue #21.
 *
 * Only two URL shapes are ones this app built: Retrofit's placeholder base,
 * and a WebDAV URL built from the stored host by `PageBodyService`. Anything
 * else arrived as content (an image ref in a shared page body, say) and gets
 * no [RequestOrigin], which is what stops [AuthInterceptor] attaching
 * Basic-auth to it.
 *
 * S-23 answered this on the hostname alone, which stopped page content
 * nominating an arbitrary *server* and not an arbitrary *path* on the user's
 * own one. [HostInterceptor.isKnownHost] is what still refuses a foreign
 * host outright; getting past it only means the request is forwarded
 * unsigned.
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
        assertEquals(
            RequestOrigin.StoredHost,
            originOf("https://Cloud.Example.COM/nextcloud/remote.php/dav/files/bob/Page.md"),
        )
    }

    @Test
    fun sameHostOcsPath_hasNoOrigin() {
        // Issue #21: the shape a planted image ref wants. Nothing in the app
        // builds an OCS URL on the stored host -- Retrofit builds them on the
        // placeholder -- so a same-host `/ocs/` path can only be content, and
        // signing it would hand page content an authenticated API call.
        assertNull(originOf("https://cloud.example.com/nextcloud/ocs/v2.php/apps/x/y?confirm=1"))
    }

    @Test
    fun sameHostAppEndpoint_hasNoOrigin() {
        assertNull(originOf("https://cloud.example.com/nextcloud/index.php/apps/settings/x?confirm=1"))
    }

    @Test
    fun sameHostWebdavPathWithoutTheSubdirectoryPrefix_hasNoOrigin() {
        // On a subdirectory install the app always builds the prefix in, so a
        // WebDAV-looking path that skips it did not come from us.
        assertNull(originOf("https://cloud.example.com/remote.php/dav/files/bob/Page.md"))
    }

    @Test
    fun webdavPrefixMatchesWhatPageBodyServiceBuilds() {
        // The provenance check and the URL builder have to agree, or every
        // page body fetch loses its credentials.
        val built = "https://cloud.example.com/nextcloud"
            .toHttpUrl()
            .newBuilder()
            .addPathSegment("remote.php")
            .addPathSegment("dav")
            .addPathSegment("files")
            .addPathSegment("bob")
            .addPathSegment("Page.md")
            .build()
        assertEquals(RequestOrigin.StoredHost, HostInterceptor.originOf(built, target))
    }

    @Test
    fun foreignHostIsRefusedRatherThanForwarded() {
        assertFalse(HostInterceptor.isKnownHost("https://evil.example.org/x.png".toHttpUrl(), target))
        assertTrue(
            HostInterceptor.isKnownHost("https://cloud.example.com/nextcloud/ocs/x".toHttpUrl(), target),
        )
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
