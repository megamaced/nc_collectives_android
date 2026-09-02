package com.megamaced.nccollectives.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [absolutizeImageRefs].
 *
 * Beyond the existing resolution rules (relative refs against the
 * attachments URL, attachment-directory refs against the page directory,
 * code regions skipped — B-4), these pin S-24 and issue #21: a ref is only
 * rendered as an image when it resolves inside the page's own directory.
 * Page bodies are shared, so a co-member with write access could otherwise
 * plant `![](https://anything/index.php/…)` and have the victim's app fetch
 * it — through the app's *authenticated* OkHttp client — merely by opening
 * the page.
 *
 * S-24 checked the host, which stopped the ref naming a *server* of the
 * attacker's choosing. It did not stop it naming a *path* of their choosing
 * on the user's own server, which is what the directory boundary is for.
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
    fun absoluteRefInsideTheAttachmentsDirectory_staysAnImage() {
        val url = "${base}other.png"
        assertEquals("![shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun absoluteRefInsideThePageDirectory_staysAnImage() {
        // A sibling page's attachments directory: pages in one folder share
        // a directory, and this is a shape Nextcloud Text really writes.
        val url = "$pageDir.attachments.99/other.png"
        assertEquals("![shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun absoluteRefElsewhereOnTheNextcloudHost_isDemotedToALink() {
        // Issue #21: same host, and previously kept as an image on that
        // basis alone. `HostInterceptor` then vouched for it and
        // `AuthInterceptor` signed it, so opening the page issued an
        // authenticated GET to a path the page author chose.
        val url = "https://cloud.example.com/remote.php/dav/files/bob/Private/tax-return.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun absoluteRefAtAnOcsEndpointOnTheNextcloudHost_isDemotedToALink() {
        val url = "https://cloud.example.com/ocs/v2.php/apps/x/y?confirm=1"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun absoluteRefThatWalksOutOfThePageDirectory_isDemotedToALink() {
        // The boundary is compared after canonicalisation, so a `..` that
        // leaves the directory is caught even though the string starts
        // inside it.
        val url = "$pageDir../../../Private/tax-return.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun attachmentDirRefThatWalksOutOfThePageDirectory_isDemotedToALink() {
        // Same escape reached through the branch that resolves an
        // attachment-directory ref against the page directory.
        val ref = ".attachments.12/../../../Private/tax-return.png"
        assertEquals("[shot]($ref)", absolutizeImageRefs("![shot]($ref)", base))
    }

    @Test
    fun absoluteHttpRefOnNextcloudHost_isDemotedToALink() {
        // Cleartext is not a URL this app builds, and `HostInterceptor`
        // would silently upgrade the scheme and fetch it with credentials
        // attached. Fail closed on a scheme change instead.
        val url = "http://cloud.example.com/remote.php/dav/files/bob/.Collectives/Wiki/x.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun refWithEmbeddedUserinfo_isDemotedToALink() {
        val url = "https://attacker:pw@cloud.example.com/remote.php/dav/files/bob/.Collectives/Wiki/x.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
    }

    @Test
    fun refOnAnUnexpectedPort_isDemotedToALink() {
        val url = "https://cloud.example.com:8443/remote.php/dav/files/bob/.Collectives/Wiki/x.png"
        assertEquals("[shot]($url)", absolutizeImageRefs("![shot]($url)", base))
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
        // No scheme, so `ImagesPlugin` has no handler for it and nothing
        // fetches it. Deliberately outside the boundary check: it is not
        // part of the fetch surface, so rewriting it would change what the
        // reader sees for no security gain.
        assertEquals(
            "![shot](/index.php/x.png)",
            absolutizeImageRefs("![shot](/index.php/x.png)", base),
        )
    }

    @Test
    fun offHostRefWithBlankAltAndNoParsableHost_fallsBackToTheRefItself() {
        // The demote label has to be non-blank or the link renders invisible.
        val ref = ".attachments.12/../../../x.png"
        assertEquals("[$ref]($ref)", absolutizeImageRefs("![]($ref)", base))
    }
}
