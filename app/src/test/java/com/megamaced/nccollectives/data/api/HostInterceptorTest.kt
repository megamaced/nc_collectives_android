package com.megamaced.nccollectives.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B-60 / GH-8: the subdirectory prefix of a stored host has to land on a
 * request exactly once, whichever of the two URL shapes the request started
 * as — Retrofit's placeholder base, or a WebDAV URL already built from the
 * stored host.
 */
class HostInterceptorTest {
    private fun retarget(
        original: String,
        host: String,
    ): String = HostInterceptor.retarget(original.toHttpUrl(), host.toHttpUrl()).toString()

    @Test
    fun `placeholder OCS url gains the subdirectory prefix`() {
        assertEquals(
            "https://example.com/nextcloud/ocs/v2.php/apps/collectives/api/v1.0/collectives",
            retarget(
                "https://placeholder.invalid/ocs/v2.php/apps/collectives/api/v1.0/collectives",
                "https://example.com/nextcloud",
            ),
        )
    }

    @Test
    fun `webdav url built from the stored host keeps a single prefix`() {
        assertEquals(
            "https://example.com/nextcloud/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
            retarget(
                "https://example.com/nextcloud/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
                "https://example.com/nextcloud",
            ),
        )
    }

    @Test
    fun `root install is unaffected`() {
        assertEquals(
            "https://example.com/ocs/v2.php/apps/collectives/api/v1.0/collectives",
            retarget(
                "https://placeholder.invalid/ocs/v2.php/apps/collectives/api/v1.0/collectives",
                "https://example.com",
            ),
        )
        assertEquals(
            "https://example.com/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
            retarget(
                "https://example.com/remote.php/dav/files/bob/Collectives/Wiki/Page.md",
                "https://example.com",
            ),
        )
    }

    @Test
    fun `nested prefix and non-default port survive the rewrite`() {
        assertEquals(
            "https://example.com:8443/apps/nc/remote.php/dav/files/bob/x.md",
            retarget(
                "https://example.com:8443/apps/nc/remote.php/dav/files/bob/x.md",
                "https://example.com:8443/apps/nc/",
            ),
        )
        assertEquals(
            "https://example.com:8443/apps/nc/status.php",
            retarget("https://placeholder.invalid/status.php", "https://example.com:8443/apps/nc/"),
        )
    }

    @Test
    fun `query string is preserved`() {
        assertEquals(
            "https://example.com/nextcloud/ocs/v2.php/search/providers/x/search?term=a",
            retarget(
                "https://placeholder.invalid/ocs/v2.php/search/providers/x/search?term=a",
                "https://example.com/nextcloud",
            ),
        )
    }
}
