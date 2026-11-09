package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #85, end to end: the `collectives-pages` unified-search reply as
 * Collectives 3.0+ sends it, through the real Retrofit service and Room.
 *
 * A page created (or renamed, moved, copied) since slugs arrived is linked as
 * `…/apps/collectives/<collective>/<slug>-<id>` with no `fileId`, and every
 * such result came back with a null page id — untappable, and labelled as not
 * cached even when the page had been created on this very device.
 */
@RunWith(AndroidJUnit4::class)
class SearchSlugLinkIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun aSluggedPageOnTheDevice_resolvesToItsPageAndCollective_andOpens() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 1234, title = "Meeting notes")
            dispatcher.on(SEARCH, searchReply(entry("Meeting notes", "$APP/Wiki/meeting-notes-1234")), method = "GET")

            val hit = hits().single()

            assertEquals(1234L, hit.pageId)
            assertEquals(IntegrationEnvironment.COLLECTIVE_ID, hit.collectiveId)
            assertTrue(hit.isOpenable)
        }

    @Test
    fun aSluggedPageNotOnTheDevice_isIdentifiedButNotOpenable() =
        runTest {
            dispatcher.on(SEARCH, searchReply(entry("Elsewhere", "$APP/Wiki/elsewhere-999")), method = "GET")

            val hit = hits().single()

            assertEquals(999L, hit.pageId)
            assertNull(hit.collectiveId)
            assertFalse(hit.isOpenable)
        }

    @Test
    fun theOtherLinkShapes_stillResolveAsBefore() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 42, title = "Old page")
            dispatcher.on(
                SEARCH,
                searchReply(
                    entry("Old page", "$APP/Wiki/Old%20page?fileId=42"),
                    entry("Wiki", "$APP/wiki-7"),
                ),
                method = "GET",
            )

            val (unslugged, landing) = hits()

            assertEquals(42L, unslugged.pageId)
            assertTrue(unslugged.isOpenable)
            // An unslugged landing page's link names no page; the collective
            // segment's own trailing id must not be read as one.
            assertNull(landing.pageId)
        }

    private suspend fun hits() =
        env.searchRepository.search("notes", limit = 25).let { result ->
            assertTrue("search should succeed, was $result", result is ApiResult.Success)
            (result as ApiResult.Success).data
        }

    /**
     * One entry as `PageProvider::search` builds it: no `attributes` (PHP's
     * empty array serialises as `[]`), the link in `resourceUrl`.
     */
    private fun entry(
        title: String,
        resourceUrl: String,
    ): String =
        """{"thumbnailUrl":"","title":"$title","subline":"In collective Wiki","resourceUrl":"$resourceUrl",""" +
            """"icon":"icon-collectives-page","rounded":false,"attributes":[]}"""

    private fun searchReply(vararg entries: String) =
        OcsResponses.envelope("""{"name":"Collectives - Pages","isPaginated":false,"entries":[${entries.joinToString(",")}]}""")

    private companion object {
        const val SEARCH = "/search/providers/collectives-pages/search"
        const val APP = "https://cloud.example.com/index.php/apps/collectives"
    }
}
