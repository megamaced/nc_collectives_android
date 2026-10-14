package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.domain.model.PageOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A page-order pick and a collective-list refresh must not overwrite each
 * other's `userPageOrder` (Codex's review of v2.13.0).
 *
 * The list response carries every collective's `userPageOrder`, so a list
 * fetched before a pick and written after it puts the old order back into
 * the cache — while the server, having accepted the pick, holds the new one.
 * Only a real in-flight request can open that window, which is why this sits
 * in the integration harness rather than beside `SetPageOrderTest`.
 */
@RunWith(AndroidJUnit4::class)
class PageOrderRefreshRaceIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var repository: CollectiveRepositoryImpl

    // The pick has to run beside the refresh, not inside the test's own
    // scheduler, or it could only ever start once the refresh had finished.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        repository =
            CollectiveRepositoryImpl(
                api = env.api,
                circlesApi = mockk<CirclesApiService>(),
                dao = env.db.collectiveDao(),
                pageDao = env.db.pageDao(),
                attachmentDao = env.db.attachmentDao(),
                editQueueDao = env.db.editQueueDao(),
                pageViewDao = env.db.pageViewDao(),
                database = env.db,
                accountGeneration = env.accountGeneration,
            )
    }

    @After
    fun tearDown() {
        background.cancel()
        env.close()
    }

    private fun collectiveList(pageOrder: Int): MockResponse =
        OcsResponses.envelope(
            """{"collectives":[{"id":${IntegrationEnvironment.COLLECTIVE_ID},"name":"Wiki","userPageOrder":$pageOrder}]}""",
        )

    @Test
    fun aPickMadeWhileAStaleListIsInFlight_isNotOverwrittenByIt() =
        runTest {
            dispatcher
                .on(COLLECTIVES_PATH, collectiveList(pageOrder = PageOrder.Custom.wire), method = "GET")
                .on(PAGE_ORDER_PATH, OcsResponses.envelope("[]"), method = "PUT")
            assertTrue(repository.refresh() is ApiResult.Success)

            // The next list fetch is the stale one: it answers "custom order"
            // whatever happens meanwhile. While it is on the wire, the user
            // picks Title A–Z — and the pick is given every chance to finish
            // before the stale reply goes out. Unserialised it did finish,
            // and the reply then overwrote it.
            val pick = CompletableDeferred<Deferred<ApiResult<Unit>>>()
            val armed = AtomicBoolean(true)
            dispatcher.whileInFlight(COLLECTIVES_PATH) { request ->
                if (request.method != "GET" || !armed.getAndSet(false)) return@whileInFlight
                val inFlight = background.async {
                    repository.setPageOrder(IntegrationEnvironment.COLLECTIVE_ID, PageOrder.TitleAscending)
                }
                pick.complete(inFlight)
                runBlocking { withTimeoutOrNull(PICK_GRACE_MS) { inFlight.await() } }
            }

            assertTrue(repository.refresh() is ApiResult.Success)

            assertTrue(pick.await().await() is ApiResult.Success)
            assertEquals(
                "the cache must hold the pick the server accepted",
                PageOrder.TitleAscending.wire,
                env.db
                    .collectiveDao()
                    .getById(IntegrationEnvironment.COLLECTIVE_ID)
                    ?.userPageOrder,
            )
        }

    private companion object {
        const val COLLECTIVES_PATH = "v1.0/collectives"
        const val PAGE_ORDER_PATH = "/userSettings/pageOrder"

        /** Plenty for an unserialised pick against a local server; a serialised one waits it out. */
        const val PICK_GRACE_MS = 500L
    }
}
