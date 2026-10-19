package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
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
 * A change to a collective setting and a collective-list refresh must not
 * overwrite each other: Codex's review of v2.13.0 for page order, and of
 * 3.0 for favorites. The emoji has the same shape.
 *
 * The list response carries every collective's page order, favorites and
 * emoji, so a list fetched before a change and written after it puts the
 * old value back into the cache — while the server, having accepted the
 * change, holds the new one. Only a real in-flight request can open that
 * window, which is why this sits in the integration harness rather than
 * beside `SetPageOrderTest`.
 */
@RunWith(AndroidJUnit4::class)
class CollectiveSettingsRefreshRaceIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var repository: CollectiveRepositoryImpl

    // The change has to run beside the refresh, not inside the test's own
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

    /** The list as the server had it before the change: custom order, pages 11 and 12 starred, no emoji. */
    private fun staleList(): MockResponse =
        OcsResponses.envelope(
            """{"collectives":[{"id":$ID,"name":"Wiki","userPageOrder":${PageOrder.Custom.wire},"userFavoritePages":[11,12]}]}""",
        )

    private suspend fun cached(): CollectiveEntity = env.db.collectiveDao().getById(ID)!!

    /**
     * Cache the stale list, then refresh again while it is still what the
     * server answers. While that list is on the wire, [change] is made, and
     * it is given every chance to finish before the stale reply goes out.
     * Unserialised it did finish, and the reply then overwrote it.
     */
    private suspend fun changeWhileAStaleListIsInFlight(change: suspend () -> ApiResult<Unit>) {
        dispatcher.on(COLLECTIVES_PATH, staleList(), method = "GET")
        assertTrue(repository.refresh() is ApiResult.Success)

        val made = CompletableDeferred<Deferred<ApiResult<Unit>>>()
        val armed = AtomicBoolean(true)
        dispatcher.whileInFlight(COLLECTIVES_PATH) { request ->
            if (request.method != "GET" || !armed.getAndSet(false)) return@whileInFlight
            val inFlight = background.async { change() }
            made.complete(inFlight)
            runBlocking { withTimeoutOrNull(CHANGE_GRACE_MS) { inFlight.await() } }
        }

        assertTrue(repository.refresh() is ApiResult.Success)
        assertTrue(made.await().await() is ApiResult.Success)
    }

    @Test
    fun aPageOrderPickIsNotOverwrittenByAStaleList() =
        runTest {
            dispatcher.on("/userSettings/pageOrder", OcsResponses.envelope("[]"), method = "PUT")

            changeWhileAStaleListIsInFlight { repository.setPageOrder(ID, PageOrder.TitleAscending) }

            assertEquals("the cache must hold the pick the server accepted", PageOrder.TitleAscending.wire, cached().userPageOrder)
        }

    @Test
    fun anUnstarIsNotOverwrittenByAStaleList() =
        runTest {
            dispatcher.on("/userSettings/favoritePages", OcsResponses.envelope("[]"), method = "PUT")

            changeWhileAStaleListIsInFlight { repository.toggleFavorite(ID, 11, favorite = false) }

            assertEquals("the cache must hold the favorites the server accepted", "12", cached().userFavoritePagesCsv)
        }

    @Test
    fun anEmojiIsNotOverwrittenByAStaleList() =
        runTest {
            dispatcher.on(
                "$COLLECTIVES_PATH/$ID",
                OcsResponses.envelope("""{"collective":{"id":$ID,"name":"Wiki","emoji":"📚"}}"""),
                method = "PUT",
            )

            changeWhileAStaleListIsInFlight { repository.setCollectiveEmoji(ID, "📚") }

            assertEquals("the cache must hold the emoji the server accepted", "📚", cached().emoji)
        }

    private companion object {
        const val ID = IntegrationEnvironment.COLLECTIVE_ID
        const val COLLECTIVES_PATH = "v1.0/collectives"

        /** Plenty for an unserialised change against a local server; a serialised one waits it out. */
        const val CHANGE_GRACE_MS = 500L
    }
}
