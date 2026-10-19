package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.DraftBase
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #34: a 412 that changed nothing. A proxy compressing the response a
 * validator came in may rewrite it, and the origin refuses that form in
 * `If-Match`. When the page is still the text the edit started from, the
 * write is tried again against the version read back uncompressed — still
 * conditional, so nothing that lands in between is overwritten. A page that
 * really changed, or a validator no form of which matches, is a conflict.
 */
@RunWith(AndroidJUnit4::class)
class SpuriousConflictIntegrationTest {
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

    private fun serverHas(
        body: String,
        etag: String,
    ) {
        dispatcher.on(".md", OcsResponses.webDav(200, etag = etag).setBody(body), method = "GET")
    }

    @Test
    fun aSaveRefusedOnlyForAProxysFormOfTheValidator_isWrittenAgainstTheOriginsOwn() =
        runTest {
            // Read through Apache compressing it: `-gzip` on the ETag.
            env.seedPage(id = 12, bodyMd = "Hello", bodyEtag = "abc-gzip")
            serverHas("Hello", etag = "\"abc\"")
            dispatcher.on(".md", OcsResponses.webDav(412), OcsResponses.webDav(204, etag = "\"def\""), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "Hello world")

            assertEquals(SaveOutcome.Saved, outcome)
            assertEquals("identity", dispatcher.requestsWithMethod("GET").single().getHeader("Accept-Encoding"))
            val puts = dispatcher.requestsWithMethod("PUT")
            assertEquals("\"abc-gzip\"", puts[0].getHeader("If-Match"))
            assertEquals("still conditional", "\"abc\"", puts[1].getHeader("If-Match"))
            val row = env.db.pageDao().getById(12)
            assertEquals("Hello world", row?.bodyMd)
            assertEquals("def", row?.bodyEtag)
            assertNull(row?.draftBodyMd)
        }

    /**
     * Someone else's edit lands between the read that matched and the write
     * that follows it. The write is conditional on the version read, so it is
     * refused, and their edit stands.
     */
    @Test
    fun anEditLandingAfterTheMatchingRead_isNotOverwritten() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Hello", bodyEtag = "abc-gzip")
            val origin = Origin(body = "Hello", etag = "abc", nextEdit = "Someone else's edit" to "xyz")
            env.server.dispatcher = origin

            val outcome = env.pageRepository.saveBody(12, "Hello world")

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals("Someone else's edit", origin.body)
            val row = env.db.pageDao().getById(12)
            assertEquals("Hello world", row?.draftBodyMd)
            assertEquals("the conflict shows what it is now", "Someone else's edit", row?.bodyMd)
            assertEquals("xyz", row?.bodyEtag)
        }

    /** An origin whose own validator is weak: no `If-Match` matches it, so there's nothing to try again. */
    @Test
    fun aValidatorNoFormOfWhichMatches_isAConflictWithoutAFutileRetry() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Hello", bodyEtag = "abc")
            serverHas("Hello", etag = "W/\"abc\"")
            dispatcher.on(".md", OcsResponses.webDav(412), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "Hello world")

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals(1, dispatcher.requestsWithMethod("PUT").size)
        }

    @Test
    fun aPageThatReallyChangedIsStillAConflict() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Hello", bodyEtag = "abc")
            serverHas("Hello from someone else", etag = "\"xyz\"")
            dispatcher.on(".md", OcsResponses.webDav(412), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "Hello world")

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals(1, dispatcher.requestsWithMethod("PUT").size)
            assertEquals(
                "Hello world",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }

    /**
     * Issue #57 meets #34: a draft written from an older version than the
     * cached one. The cached body is not what it started from, so there is
     * nothing to check it against, and it is a conflict as before.
     */
    @Test
    fun aDraftFromAnOlderVersionIsNotWrittenThrough() =
        runTest {
            env.seedPage(id = 12, bodyMd = "B's text", bodyEtag = "B")
            serverHas("B's text", etag = "\"B\"")
            dispatcher.on(".md", OcsResponses.webDav(412), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "my edit of A", basedOn = DraftBase.of("A's text", "A"))

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals(1, dispatcher.requestsWithMethod("PUT").size)
        }

    /**
     * A WebDAV origin that holds one version of the page and honours
     * `If-Match` against it. [nextEdit] lands right after the first read, as
     * another client's save would.
     */
    private class Origin(
        @Volatile var body: String,
        @Volatile var etag: String,
        private var nextEdit: Pair<String, String>?,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.method) {
                "GET" -> {
                    val response = OcsResponses.webDav(200, etag = "\"$etag\"").setBody(body)
                    nextEdit?.let { (newBody, newEtag) ->
                        body = newBody
                        etag = newEtag
                    }
                    nextEdit = null
                    response
                }

                "PUT" -> {
                    if (request.getHeader("If-Match") != "\"$etag\"") {
                        OcsResponses.webDav(412)
                    } else {
                        body = request.body.readUtf8()
                        etag = "after-$etag"
                        OcsResponses.webDav(204, etag = "\"$etag\"")
                    }
                }

                else -> {
                    MockResponse().setResponseCode(501)
                }
            }
    }
}
