package com.megamaced.nccollectives.integration

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.ui.components.PageDirectorySchemeHandler
import com.megamaced.nccollectives.ui.components.pageImageBoundary
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Issue #41: the page-directory boundary has to hold at the point the
 * request is made, not only on the markdown that produced it.
 *
 * `absolutizeImageRefs` sees inline `![alt](target)` and nothing else, so a
 * CommonMark reference image (`![alt][ref]` with the target on its own
 * `[ref]: …` line) or an HTML `<img src>` — both of which Markwon renders —
 * reached `ImagesPlugin` with an absolute URL that had never been checked.
 * `HostInterceptor.isAppBuiltPath` then saw a `/remote.php/dav/files/` path
 * on the stored host, vouched for it, and `AuthInterceptor` signed it.
 *
 * These run the real handler over the real authenticated client against a
 * real server, because the property being tested is "no request leaves the
 * device" — which is not observable from a string function, and is exactly
 * what a text-rewriting test would have claimed to prove while the reference
 * syntax walked straight past it.
 */
@RunWith(AndroidJUnit4::class)
class ImageFetchBoundaryIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    /** What `PageViewScreen` passes: the page's own attachments directory. */
    private val attachmentsBase: String
        get() = "${env.host}/remote.php/dav/files/alice/.Collectives/Wiki/.attachments.12/"

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        dispatcher.on(
            "/remote.php/dav",
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody("not-really-a-png"),
        )
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun aReferenceStyleImageInsideThePageDirectory_isFetched() {
        val url = "${attachmentsBase}photo.png"

        val item = handler().handle(url, Uri.parse(url))

        assertNotNull(item)
        val request = dispatcher.requests.single()
        assertEquals("/remote.php/dav/files/alice/.Collectives/Wiki/.attachments.12/photo.png", request.path)
        assertTrue(
            "an image the page legitimately owns still goes out authenticated",
            request.getHeader("Authorization")?.startsWith("Basic ") == true,
        )
    }

    @Test
    fun aSiblingPagesAttachmentDirectory_isFetched() {
        // Pages in one folder share a directory, and `.attachments.<sibling>`
        // is a shape Nextcloud Text really writes — the boundary is the page
        // directory, not the attachments directory, precisely so this keeps
        // rendering.
        val url = "${env.host}/remote.php/dav/files/alice/.Collectives/Wiki/.attachments.99/photo.png"

        handler().handle(url, Uri.parse(url))

        assertEquals(1, dispatcher.requests.size)
    }

    @Test
    fun aReferenceStyleImageElsewhereInTheUsersWebDavTree_neverLeavesTheDevice() {
        // The planted ref from the issue: a private file the page's author
        // cannot read but can make the *victim's* app request.
        val url = "${env.host}/remote.php/dav/files/alice/Private/tax-return.pdf"

        val failure = runCatching { handler().handle(url, Uri.parse(url)) }.exceptionOrNull()

        assertTrue("refusal should be an IOException, was $failure", failure is IOException)
        assertTrue("nothing at all should have been requested", dispatcher.requests.isEmpty())
    }

    @Test
    fun theUnguardedHandlerDoesSendIt_whichIsWhatTheGateIsFor() {
        // Keeps the assertions above honest. Markwon's own handler, the one
        // that was registered directly before this fix, fetches the planted
        // ref *and* the chain signs it — so "nothing left the device" in the
        // test above is the gate working, not the request being impossible.
        val url = "${env.host}/remote.php/dav/files/alice/Private/tax-return.pdf"

        OkHttpNetworkSchemeHandler.create(env.client).handle(url, Uri.parse(url))

        val leaked = dispatcher.requests.single()
        assertEquals("/remote.php/dav/files/alice/Private/tax-return.pdf", leaked.path)
        assertTrue(
            "and it would have carried the user's credentials",
            leaked.getHeader("Authorization")?.startsWith("Basic ") == true,
        )
    }

    @Test
    fun aPathWalkingOutOfThePageDirectory_neverLeavesTheDevice() {
        val url = "$attachmentsBase../../../../Private/photo.png"

        runCatching { handler().handle(url, Uri.parse(url)) }

        assertTrue(dispatcher.requests.isEmpty())
    }

    @Test
    fun anHtmlImageOnAnotherHost_neverLeavesTheDevice() {
        // A tracking pixel rather than a credential leak — `HostInterceptor`
        // would refuse to route it — but "merely viewing a shared page pings
        // the author's server" is the same class of thing, and the gate is
        // where it stops.
        val url = "https://tracker.example/pixel.png"

        val failure = runCatching { handler().handle(url, Uri.parse(url)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(dispatcher.requests.isEmpty())
    }

    @Test
    fun noBoundaryAtAll_failsClosed() {
        // A screen with no resolvable attachments URL used to run no rewrite
        // pass at all, which meant an absolute ref was fetched unchecked.
        // Null has to mean "nothing", not "anything".
        val handler = PageDirectorySchemeHandler(
            delegate = OkHttpNetworkSchemeHandler.create(env.client),
            boundary = pageImageBoundary(null),
        )
        val url = "${attachmentsBase}photo.png"

        val failure = runCatching { handler.handle(url, Uri.parse(url)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(dispatcher.requests.isEmpty())
    }

    @Test
    fun theBoundaryIsThePageDirectoryNotTheAttachmentsDirectory() {
        val boundary = pageImageBoundary(attachmentsBase)

        assertEquals(
            "/remote.php/dav/files/alice/.Collectives/Wiki/",
            boundary?.encodedPath,
        )
    }

    @Test
    fun aBaseUrlThatIsNotAnAttachmentsDirectory_narrowsRatherThanWidens() {
        // Fail closed on a shape we don't recognise: the boundary becomes the
        // base itself, which is narrower than its parent.
        val odd = "${env.host}/remote.php/dav/files/alice/somewhere/"

        val boundary = pageImageBoundary(odd)

        assertEquals("/remote.php/dav/files/alice/somewhere/", boundary?.encodedPath)
        assertNull(
            "and a sibling of it is outside",
            "${env.host}/remote.php/dav/files/alice/elsewhere/x.png"
                .toHttpUrlOrNull()
                ?.takeIf { boundary != null && it.encodedPath.startsWith(boundary.encodedPath) },
        )
    }

    private fun handler() =
        PageDirectorySchemeHandler(
            delegate = OkHttpNetworkSchemeHandler.create(env.client),
            boundary = pageImageBoundary(attachmentsBase),
        )
}
