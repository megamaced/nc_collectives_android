package com.megamaced.nccollectives.integration

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.auth.AccountBinding
import com.megamaced.nccollectives.data.auth.AccountChangedException
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.repository.PageRepositoryImpl
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #20: a response that was already on the wire when the account
 * changed must not be written into the incoming account's cache.
 *
 * The guard is `AccountGeneration`, and its correctness argument is about
 * *transaction ordering* — the generation is captured before the request goes
 * out and re-read inside the transaction that writes the reply, so Room's
 * serialisation of transactions is what makes it airtight rather than merely
 * narrow. `AccountGenerationTest` can pin the counter's arithmetic; only a
 * real database and a real in-flight request can show that the guard is in
 * the right place.
 *
 * `PageEntity` keys on the raw *server* id, which is what makes this worth a
 * test of its own: two Nextclouds will both happily have a page 17, so a
 * leaked write is not a stale row, it is one account's content presented as
 * another's.
 */
@RunWith(AndroidJUnit4::class)
class AccountSwitchRaceIntegrationTest {
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
    fun refreshWhoseResponseLandsAfterAWipe_writesNothing() =
        runTest {
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(
                        OcsResponses.page(id = 17, title = "Account A's page"),
                        OcsResponses.page(id = 18, title = "Another of A's"),
                    ),
                    method = "GET",
                )
                // The switch lands while the page list is still in flight — the
                // exact window `cancelUniqueWork` cannot close, because it is
                // asynchronous and `recordPutOutcome` runs `NonCancellable`.
                .whileInFlight("/pages") { env.accountGeneration.invalidate() }

            val result = env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            // The *call* succeeded — the server answered. What must not happen is
            // the commit.
            assertTrue("the request itself should succeed, was $result", result is ApiResult.Success)
            assertNull("page 17 belongs to the outgoing account", env.db.pageDao().getById(17))
            assertNull(env.db.pageDao().getById(18))
            assertTrue(
                "no page rows at all should have been written",
                env.db
                    .pageDao()
                    .idsForCollective(IntegrationEnvironment.COLLECTIVE_ID)
                    .isEmpty(),
            )
        }

    @Test
    fun refreshWithNoSwitch_commitsNormally() =
        runTest {
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17, title = "Kept")), method = "GET")

            val result = env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertTrue(result is ApiResult.Success)
            assertEquals(
                "Kept",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.title,
            )
        }

    @Test
    fun aWipeBeforeTheRequestGoesOut_doesNotBlockTheNextAccountsRefresh() =
        runTest {
            // The generation is captured per call, so a switch that has already
            // happened must not poison refreshes issued afterwards — otherwise
            // signing into the second account would show an empty app. A
            // switch has happened once it has settled, as `SessionManager`
            // does when it ends one.
            env.accountGeneration.invalidate()
            env.accountGeneration.settle()
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17, title = "Account B's page")), method = "GET")

            env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertNotNull(env.db.pageDao().getById(17))
            assertEquals(
                "Account B's page",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.title,
            )
        }

    @Test
    fun aWipeMidRefresh_doesNotDeleteTheIncomingAccountsRows() =
        runTest {
            // The abandoned transaction also carries the reconciliation delete.
            // Bailing out has to skip that too, or a switch landing mid-refresh
            // would take the *new* account's rows with it.
            env.seedPage(id = 17, title = "Already here")
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "Different page")), method = "GET")
                .whileInFlight("/pages") { env.accountGeneration.invalidate() }

            env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertNotNull("the existing row must not be reconciled away", env.db.pageDao().getById(17))
            assertNull("and the abandoned response must not be inserted", env.db.pageDao().getById(99))
        }

    @Test
    fun aPageCreatedAsTheAccountChanges_isNotRecorded() =
        runTest {
            env.seedPage(id = 1, title = "Landing")
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                .whileInFlight("/pages/1") { env.accountGeneration.invalidate() }

            val result = env.pageRepository.createPage(
                collectiveId = IntegrationEnvironment.COLLECTIVE_ID,
                parentPageId = 1,
                title = "New",
                body = "",
            )

            assertTrue("the outgoing account's page must not be handed back, was $result", result is ApiResult.Unexpected)
            assertNull("page 50 belongs to the outgoing account", env.db.pageDao().getById(50))
        }

    @Test
    fun aPageCreatedWithNoSwitch_isRecorded() =
        runTest {
            env.seedPage(id = 1, title = "Landing")
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(
                        OcsResponses.page(id = 1, title = "Landing"),
                        OcsResponses.page(id = 50, title = "New", parentId = 1),
                    ),
                    method = "GET",
                )

            val result = env.pageRepository.createPage(
                collectiveId = IntegrationEnvironment.COLLECTIVE_ID,
                parentPageId = 1,
                title = "New",
                body = "",
            )

            assertTrue("was $result", result is ApiResult.Success)
            assertEquals(
                "New",
                env.db
                    .pageDao()
                    .getById(50)
                    ?.title,
            )
        }

    @Test
    fun aSwitchTheMomentACreatedPageIsRecorded_isNotUndoneByTheFollowUpRefresh() =
        runTest {
            env.seedPage(id = 1, title = "Landing")
            val wipe = AtomicReference<Job?>()
            val real = env.db.pageDao()
            // The switch lands while the insert's transaction still holds
            // Room, so its wipe queues behind it and runs straight after.
            val pageDao = object : PageDao by real {
                override suspend fun upsertAll(pages: List<PageEntity>) {
                    real.upsertAll(pages)
                    if (pages.singleOrNull()?.id == 50L && wipe.get() == null) {
                        env.accountGeneration.invalidate()
                        wipe.set(
                            backgroundScope.launch(Dispatchers.IO) {
                                env.db.withTransaction { env.db.pageDao().clear() }
                                env.seedPage(id = 75, title = "Incoming account's page")
                            },
                        )
                    }
                }
            }
            val repository = PageRepositoryImpl(
                api = env.api,
                bodyService = env.bodyService,
                pageDao = pageDao,
                editQueueDao = env.db.editQueueDao(),
                attachmentDao = env.db.attachmentDao(),
                pageViewDao = env.db.pageViewDao(),
                syncScheduler = env.syncScheduler,
                database = env.db,
                accountGeneration = env.accountGeneration,
                attachmentRepository = { env.attachmentRepository as AttachmentRepository },
            )
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(OcsResponses.page(id = 1), OcsResponses.page(id = 50, title = "New", parentId = 1)),
                    method = "GET",
                )
                // Still the outgoing account's answer; hold it until the
                // incoming account's page is in.
                .whileInFlight("/pages") { request -> if (request.method == "GET") runBlocking { wipe.get()?.join() } }

            val result = repository.createPage(IntegrationEnvironment.COLLECTIVE_ID, parentPageId = 1, title = "New", body = "")
            wipe.get()?.join()

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertNull("the outgoing account's page must stay wiped", env.db.pageDao().getById(50))
            assertNotNull("and the incoming account's must survive the refresh", env.db.pageDao().getById(75))
        }

    @Test
    fun aCreatedPagesBodyConflictingAsTheAccountChanges_isNotQueuedForTheNextOne() =
        runTest {
            env.seedPage(id = 1, title = "Landing")
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(OcsResponses.page(id = 1), OcsResponses.page(id = 50, title = "New", parentId = 1)),
                    method = "GET",
                )
                // The switch lands during the follow-up refresh, after the
                // page is recorded and before its body is saved, so a save
                // that captured its own generation would capture the next
                // account's.
                .whileInFlight("/pages") { request -> if (request.method == "GET") env.accountGeneration.invalidate() }
                // And the body's PUT loses a race, which would ordinarily
                // park it as a conflict draft with a CONFLICTED queue row.
                .on("New.md", OcsResponses.webDav(412), method = "PUT")

            val result = env.pageRepository.createPage(
                collectiveId = IntegrationEnvironment.COLLECTIVE_ID,
                parentPageId = 1,
                title = "New",
                body = "The outgoing account's text",
            )

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertNull("nothing may be queued under the next account", env.db.editQueueDao().forPage(50))
            assertNull(
                env.db
                    .pageDao()
                    .getById(50)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun aSwitchDuringTheFollowUpRefresh_sendsTheBodyNowhere() =
        runTest {
            env.seedPage(id = 1, title = "Landing")
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(OcsResponses.page(id = 1), OcsResponses.page(id = 50, title = "New", parentId = 1)),
                    method = "GET",
                ).on("Incoming.md", OcsResponses.webDav(204, etag = "incoming-new"), method = "PUT")
                .whileInFlight("/pages") { request ->
                    if (request.method == "GET") {
                        // The whole switch lands while the refresh is out, and
                        // the next account happens to have a page 50 too.
                        env.accountGeneration.invalidate()
                        runBlocking {
                            env.db.withTransaction { env.db.pageDao().clear() }
                            env.seedPage(
                                id = 50,
                                title = "Incoming",
                                fileName = "Incoming.md",
                                collectivePath = "IncomingCollection",
                                bodyMd = "Incoming body",
                                bodyEtag = "incoming-etag",
                            )
                        }
                        every { env.tokenStore.getCredentials() } returns
                            StoredCredentials(env.host, "incoming-user", "incoming-password")
                    }
                }

            val result = env.pageRepository.createPage(IntegrationEnvironment.COLLECTIVE_ID, 1, "New", "OUTGOING BODY")

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertTrue("no body may be written anywhere", dispatcher.requestsWithMethod("PUT").isEmpty())
            assertEquals(
                "Incoming body",
                env.db
                    .pageDao()
                    .getById(50)
                    ?.bodyMd,
            )
        }

    @Test
    fun aRequestMadeBeforeASwitch_isNotSentUnderTheNextAccount() =
        runTest {
            // A second server on the same hostname, as the next account's.
            env.server.dispatcher = RoutingDispatcher().on("/", OcsResponses.webDav(200), method = "GET")
            val originCert = env.client.newCall(Request.Builder().url(env.host).build()).execute().use {
                it.handshake!!.peerCertificates.first() as X509Certificate
            }
            val incomingCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val trusted = HandshakeCertificates
                .Builder()
                .addTrustedCertificate(originCert)
                .addTrustedCertificate(incomingCert.certificate)
                .build()
            MockWebServer().use { incoming ->
                incoming.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(incomingCert)
                        .build()
                        .sslSocketFactory(),
                    false,
                )
                incoming.start()
                val received = RoutingDispatcher().on("/", OcsResponses.webDav(204), method = "PUT")
                incoming.dispatcher = received
                env.seedPage(id = 1, title = "Landing")
                val client = env.client
                    .newBuilder()
                    .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                    .apply {
                        interceptors().add(
                            0,
                            Interceptor { chain ->
                                if (chain.request().method == "PUT") {
                                    // The repository has made the request; the
                                    // switch lands before the interceptors
                                    // that read the host and password run.
                                    env.accountGeneration.invalidate()
                                    every { env.tokenStore.getCredentials() } returns
                                        StoredCredentials(
                                            incoming.url("/").toString().trimEnd('/'),
                                            IntegrationEnvironment.LOGIN_NAME,
                                            "incoming",
                                        )
                                }
                                chain.proceed(chain.request())
                            },
                        )
                    }.build()
                val repository = PageRepositoryImpl(
                    api = env.api,
                    bodyService = PageBodyService(client, env.tokenStore),
                    pageDao = env.db.pageDao(),
                    editQueueDao = env.db.editQueueDao(),
                    attachmentDao = env.db.attachmentDao(),
                    pageViewDao = env.db.pageViewDao(),
                    syncScheduler = env.syncScheduler,
                    database = env.db,
                    accountGeneration = env.accountGeneration,
                    attachmentRepository = { env.attachmentRepository as AttachmentRepository },
                )
                env.server.dispatcher = RoutingDispatcher()
                    .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                    .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                    .on(
                        "/pages",
                        OcsResponses.pageList(OcsResponses.page(id = 1), OcsResponses.page(id = 50, title = "New", parentId = 1)),
                        method = "GET",
                    )

                val result = repository.createPage(IntegrationEnvironment.COLLECTIVE_ID, 1, "New", "OUTGOING BODY")

                assertTrue("was $result", result is ApiResult.Unexpected)
                assertTrue("the next account's server must hear nothing", received.requestsWithMethod("PUT").isEmpty())
                assertNull(env.db.editQueueDao().forPage(50))
            }
        }

    @Test
    fun aBoundRetrofitCallIsRefusedOnceItsAccountHasGone_andAnUnboundOneIsNot() =
        runTest {
            dispatcher.on("/pages", OcsResponses.pageList(), method = "GET")
            val generation = env.accountGeneration.current()
            env.accountGeneration.invalidate()

            val bound = withContext(AccountBinding(generation)) { apiCall { env.api.listPages(IntegrationEnvironment.COLLECTIVE_ID) } }
            val unbound = apiCall { env.api.listPages(IntegrationEnvironment.COLLECTIVE_ID) }

            assertTrue("was $bound", bound is ApiResult.NetworkError && bound.cause is AccountChangedException)
            assertTrue("was $unbound", unbound is ApiResult.Success)
            assertEquals("only the unbound call went out", 1, dispatcher.requestsTo("/pages").size)
        }

    @Test
    fun aBoundCallWhoseAccountIsCurrentGoesOut() =
        runTest {
            dispatcher.on("/pages", OcsResponses.pageList(), method = "GET")

            val result = withContext(AccountBinding(env.accountGeneration.current())) {
                apiCall { env.api.listPages(IntegrationEnvironment.COLLECTIVE_ID) }
            }

            assertTrue("was $result", result is ApiResult.Success)
        }

    /** A client whose first interceptor runs [beforeHostAndAuth] on each PUT, trusting [incoming] as well. */
    private fun clientWith(
        incoming: HeldCertificate,
        beforeHostAndAuth: () -> Unit,
    ): okhttp3.OkHttpClient {
        dispatcher.on("/", OcsResponses.webDav(200), method = "GET")
        val originCert = env.client.newCall(Request.Builder().url(env.host).build()).execute().use {
            it.handshake!!.peerCertificates.first() as X509Certificate
        }
        val trusted = HandshakeCertificates
            .Builder()
            .addTrustedCertificate(originCert)
            .addTrustedCertificate(incoming.certificate)
            .build()
        return env.client
            .newBuilder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .apply {
                interceptors().add(
                    0,
                    Interceptor { chain ->
                        if (chain.request().method == "PUT") beforeHostAndAuth()
                        chain.proceed(chain.request())
                    },
                )
            }.build()
    }

    private fun repositoryOver(client: okhttp3.OkHttpClient) =
        PageRepositoryImpl(
            api = env.api,
            bodyService = PageBodyService(client, env.tokenStore),
            pageDao = env.db.pageDao(),
            editQueueDao = env.db.editQueueDao(),
            attachmentDao = env.db.attachmentDao(),
            pageViewDao = env.db.pageViewDao(),
            syncScheduler = env.syncScheduler,
            database = env.db,
            accountGeneration = env.accountGeneration,
            attachmentRepository = { env.attachmentRepository as AttachmentRepository },
        )

    @Test
    fun workStartedWhileASwitchIsUnderWay_reachesNeitherAccount() =
        runTest {
            val incomingCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            MockWebServer().use { incoming ->
                incoming.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(incomingCert)
                        .build()
                        .sslSocketFactory(),
                    false,
                )
                incoming.start()
                val received = RoutingDispatcher().on("/", OcsResponses.webDav(204), method = "PUT")
                incoming.dispatcher = received
                env.seedPage(id = 1, title = "Landing")
                // The wipe has begun: the outgoing account's credentials and
                // cache are still in place.
                env.accountGeneration.invalidate()
                val client = clientWith(incomingCert) {
                    // The switch lands while the work is under way, without
                    // invalidating again.
                    every { env.tokenStore.getCredentials() } returns
                        StoredCredentials(incoming.url("/").toString().trimEnd('/'), IntegrationEnvironment.LOGIN_NAME, "incoming")
                    env.accountGeneration.settle()
                }
                dispatcher
                    .on("/pages/1", OcsResponses.singlePage(id = 50, title = "New", parentId = 1), method = "POST")
                    .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                    .on(
                        "/pages",
                        OcsResponses.pageList(OcsResponses.page(id = 1), OcsResponses.page(id = 50, title = "New", parentId = 1)),
                        method = "GET",
                    )

                val result = repositoryOver(client).createPage(IntegrationEnvironment.COLLECTIVE_ID, 1, "New", "OUTGOING BODY")

                assertTrue("was $result", result is ApiResult.NetworkError && result.cause is AccountChangedException)
                assertTrue("the next account's server must hear nothing", received.requestsWithMethod("PUT").isEmpty())
                assertTrue("nor may the outgoing one, mid-switch", dispatcher.requestsWithMethod("POST").isEmpty())
                assertNull(env.db.pageDao().getById(50))
            }
        }

    @Test
    fun replacingWithADraft_isNotSentUnderTheNextAccount() =
        runTest {
            val incomingCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            MockWebServer().use { incoming ->
                incoming.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(incomingCert)
                        .build()
                        .sslSocketFactory(),
                    false,
                )
                incoming.start()
                val received = RoutingDispatcher().on("/", OcsResponses.webDav(204), method = "PUT")
                incoming.dispatcher = received
                env.seedPage(id = 1, title = "Landing", draftBodyMd = "OUTGOING DRAFT")
                val client = clientWith(incomingCert) {
                    env.accountGeneration.invalidate()
                    every { env.tokenStore.getCredentials() } returns
                        StoredCredentials(incoming.url("/").toString().trimEnd('/'), IntegrationEnvironment.LOGIN_NAME, "incoming")
                }

                val result = repositoryOver(client).replaceWithDraft(1, "OUTGOING DRAFT")

                assertFalse("was $result", result is SaveOutcome.Saved)
                assertTrue("the next account's server must hear nothing", received.requestsWithMethod("PUT").isEmpty())
            }
        }
}
