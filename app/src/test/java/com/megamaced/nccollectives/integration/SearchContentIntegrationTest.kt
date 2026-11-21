package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.isAccountChanged
import com.megamaced.nccollectives.domain.model.SearchHit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchContentIntegrationTest {
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
    fun titlesArriveFirst_andContentAddsUniquePagesWithTheRightTitleAndSnippet() =
        runTest {
            env.seedCollective()
            env.seedCollective(id = 8)
            env.seedPage(id = 10, title = "Proxy notes")
            env.seedPage(id = 20, title = "Runbook")
            env.seedPage(id = 30, collectiveId = 8, title = "Runbook")
            dispatcher.on(TITLES, complete(entry("Proxy notes", "$APP/team-7/proxy-notes-10")))
            dispatcher.on(
                CONTENT,
                complete(
                    entry("proxy + check on startup", "$APP/team-7/Legacy%20notes?fileId=10"),
                    entry("proxy + check after deploy", "$APP/team-7/Folder/Runbook?fileId=20", "In collective Team - Folder"),
                    entry("proxy + check before deploy", "$APP/ops-8/runbook-30"),
                    entry("second excerpt", "$APP/ops-8/runbook-30"),
                ),
            )
            var first: List<SearchHit>? = null

            val hits = search("  proxy + check  ", limit = 2) { titles ->
                assertTrue(dispatcher.requestsTo(CONTENT).isEmpty())
                first = titles
            }

            assertEquals(listOf(10L), first?.map { it.pageId })
            assertEquals(listOf(10L, 20L, 30L), hits.map { it.pageId })
            assertEquals(listOf("Proxy notes", "Runbook", "Runbook"), hits.map { it.title })
            assertEquals("proxy + check after deploy", hits[1].snippet)
            assertEquals("In collective Team - Folder", hits[1].context)
            assertEquals(8L, hits.last().collectiveId)
            assertTrue(hits.all { it.isOpenable })
            assertEquals(listOf("/ocs/v2.php$TITLES", "/ocs/v2.php$CONTENT"), dispatcher.requests.map { it.requestUrl?.encodedPath })
            dispatcher.requests.forEach { request ->
                assertEquals("proxy + check", request.requestUrl?.queryParameter("term"))
                assertEquals("2", request.requestUrl?.queryParameter("limit"))
                assertNull(request.requestUrl?.queryParameter("cursor"))
            }
        }

    @Test
    fun contentOnlyMatches_includeLandingPagesAndIdentifyUncachedPages() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 71, parentId = 0, title = "Landing page")
            dispatcher.on(TITLES, complete())
            dispatcher.on(
                CONTENT,
                complete(
                    entry("proxy on the landing page", "$APP/Collective%207"),
                    entry("proxy in an uncached page", "$APP/team-7/uncached-99"),
                ),
            )

            val (landing, uncached) = search()

            assertEquals(71L, landing.pageId)
            assertEquals("Landing page", landing.title)
            assertEquals("proxy on the landing page", landing.snippet)
            assertTrue(landing.isOpenable)
            assertEquals(99L, uncached.pageId)
            assertEquals("proxy in an uncached page", uncached.title)
            assertFalse(uncached.isOpenable)
        }

    @Test
    fun missingContentProvider_keepsTitleHits() = runTest { assertTitleFallback(MockResponse().setResponseCode(404)) }

    @Test
    fun contentHttpFailure_keepsTitleHits() = runTest { assertTitleFallback(MockResponse().setResponseCode(500)) }

    @Test
    fun contentReplyWithoutABody_keepsTitleHits() = runTest { assertTitleFallback(MockResponse().setResponseCode(204)) }

    @Test
    fun interruptedContentReply_keepsTitleHits() =
        runTest {
            assertTitleFallback(
                complete(entry("proxy reference", "$APP/team-7/runbook-20"))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
        }

    @Test
    fun malformedContentReply_keepsTitleHits() = runTest { assertTitleFallback(OcsResponses.envelope("[]")) }

    @Test
    fun contentOcsFailure_keepsTitleHits() =
        runTest {
            assertTitleFallback(
                MockResponse().setBody(
                    """{"ocs":{"meta":{"status":"failure","statuscode":997},"data":${result(entry("ignore", "$APP/team-7/other-55"))}}}""",
                ),
            )
        }

    @Test
    fun titleNetworkFailure_reportsTheErrorAndSkipsContent_evenWithMatchingCache() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 10, title = "Proxy notes", bodyMd = "proxy reference")
            dispatcher.on(
                TITLES,
                complete(entry("Proxy notes", "$APP/team-7/proxy-notes-10"))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )

            val result = env.searchRepository.search("proxy") { error("Title hits were published after a network error") }

            assertTrue("Expected a network failure, got $result", result is ApiResult.NetworkError)
            assertTrue(dispatcher.requestsTo(CONTENT).isEmpty())
        }

    @Test
    fun anAccountSwitchDuringContent_abandonsTheCombinedResult() =
        runTest {
            dispatcher.on(TITLES, complete(entry("Proxy notes", "$APP/team-7/proxy-notes-10")))
            dispatcher.on(CONTENT, complete(entry("proxy reference", "$APP/team-7/runbook-20")))
            dispatcher.whileInFlight(CONTENT) { env.accountGeneration.invalidate() }

            assertTrue(env.searchRepository.search("proxy").isAccountChanged())
        }

    private suspend fun assertTitleFallback(content: MockResponse) {
        env.seedCollective()
        env.seedPage(id = 10, title = "Proxy notes")
        dispatcher.on(TITLES, complete(entry("Proxy notes", "$APP/team-7/proxy-notes-10")))
        dispatcher.on(CONTENT, content)

        val hit = search().single()

        assertEquals(10L, hit.pageId)
        assertEquals("Proxy notes", hit.title)
        assertTrue(hit.isOpenable)
        assertEquals(1, dispatcher.requestsTo(CONTENT).size)
    }

    private suspend fun search(
        term: String = "proxy",
        limit: Int = 25,
        onTitleHits: (List<SearchHit>) -> Unit = {},
    ): List<SearchHit> {
        val result = env.searchRepository.search(term, limit, onTitleHits)
        assertTrue("Search failed: $result", result is ApiResult.Success)
        return (result as ApiResult.Success).data
    }

    private fun complete(vararg entries: String) = OcsResponses.envelope(result(*entries))

    private fun result(vararg entries: String): String =
        """{"name":"Collectives","isPaginated":false,"cursor":null,"entries":[${entries.joinToString(",")}]}"""

    private fun entry(
        title: String,
        resourceUrl: String,
        subline: String = "In collective Team",
    ): String =
        """{"thumbnailUrl":"","title":"$title","subline":"$subline","resourceUrl":"$resourceUrl",""" +
            """"icon":"icon-collectives-page","rounded":false,"attributes":[]}"""

    private companion object {
        const val TITLES = "/search/providers/collectives-pages/search"
        const val CONTENT = "/search/providers/collectives-page-content/search"
        const val APP = "https://cloud.example.com/nextcloud/index.php/apps/collectives"
    }
}
