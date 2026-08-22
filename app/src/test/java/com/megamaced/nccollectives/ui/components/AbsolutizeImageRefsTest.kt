package com.megamaced.nccollectives.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [absolutizeImageRefs].
 *
 * Beyond the existing resolution rules (relative refs against the
 * attachments URL, attachment-directory refs against the page directory,
 * code regions skipped — B-4), these pin S-24: an absolute `http(s)` ref is
 * only rendered as an image when its host is the Nextcloud host. Page
 * bodies are shared, so a co-member with write access could otherwise
 * plant `![](https://anything/index.php/…)` and have the victim's app fetch
 * it — through the app's *authenticated* OkHttp client — merely by opening
 * the page.
 */
class AbsolutizeImageRefsTest {
    private val base =
        "https://cloud.example.com/remote.php/dav/files/bob/.Collectives/Wiki/.attachments.12/"
    private val pageDir =
        "https://cloud.example.com/remote.php/dav/files/bob/.Collectives/Wiki/"

    @Test
    fun relativeRef_isResolvedAgainstAttachmentsUrl() {
        assertEquals(
            "![shot](${base}shot.png)",
            absolutizeImageRefs("![shot](shot.png)", base),
        )
    }

    @Test
    fun attachmentDirRef_isResolvedAgainstPageDirectory() {
        assertEquals(
            "![shot]($pageDir.attachments.12/shot.png)",
            absolutizeImageRefs("![shot](.attachments.12/shot.png)", base),
        )
    }

    @Test
    fun absoluteRefOnNextcloudHost_staysAnImage() {
        val url = "https://cloud.example.com/remote.php/dav/files/bob/other.png"
        assertEquals("![shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun absoluteHttpRefOnNextcloudHost_staysAnImage() {
        // The host is what is being checked; `HostInterceptor` upgrades the
        // scheme to https at request time (S-21).
        val url = "http://cloud.example.com/remote.php/dav/files/bob/other.png"
        assertEquals("![shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun offHostRef_isDemotedToALink() {
        val url = "https://evil.example.org/track.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun offHostRefAimedAtAnAppEndpoint_isDemotedToALink() {
        // The request-forgery shape: an attacker-chosen path + query that
        // `HostInterceptor` would previously have re-pointed at the victim's
        // own server, with Basic-auth attached.
        val url = "https://anything/index.php/apps/settings/x?confirm=1"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun offHostRefWithBlankAlt_isLabelledWithItsHost() {
        assertEquals(
            "[evil.example.org](https://evil.example.org/track.png)",
            absolutizeImageRefs("![](https://evil.example.org/track.png)", base),
        )
    }

    @Test
    fun offHostRefWithTitle_keepsTheTitleOnTheLink() {
        assertEquals(
            "[shot](https://evil.example.org/t.png \"hi\")",
            absolutizeImageRefs("![shot](https://evil.example.org/t.png \"hi\")", base),
        )
    }

    @Test
    fun subdomainOfNextcloudHost_isTreatedAsOffHost() {
        // Stricter than the login flow's subdomain rule on purpose: this is
        // the host an authenticated fetch would be aimed at, and it has to
        // agree with `AuthInterceptor`, which requires exact equality.
        val url = "https://files.cloud.example.com/x.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun dataAndFileRefs_areStillNeutralisedAsRelative() {
        // S-8: resolved against the base so they reach the http(s)-only
        // scheme handler and fail there, rather than being honoured.
        assertEquals(
            "![x](${base}data:image/png;base64,AAAA)",
            absolutizeImageRefs("![x](data:image/png;base64,AAAA)", base),
        )
        assertEquals(
            "![x]($base/etc/passwd)",
            absolutizeImageRefs("![x](file:///etc/passwd)", base),
        )
    }

    @Test
    fun offHostRefInsideFencedCodeBlock_isLeftAlone() {
        val input =
            """
            Text
            ```
            ![shot](https://evil.example.org/track.png)
            ```
            More
            """.trimIndent()
        assertEquals(input, absolutizeImageRefs(input, base))
    }

    @Test
    fun offHostRefInsideInlineCode_isLeftAlone() {
        val input = "Use `![shot](https://evil.example.org/track.png)` in a page."
        assertEquals(input, absolutizeImageRefs(input, base))
    }

    @Test
    fun rootRelativeRef_isLeftAlone() {
        // No scheme, so nothing fetches it; pre-existing behaviour.
        assertEquals(
            "![shot](/index.php/x.png)",
            absolutizeImageRefs("![shot](/index.php/x.png)", base),
        )
    }
}
