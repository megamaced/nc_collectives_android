package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.domain.model.CollectivePageMode
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A collective's page mode, as set on the web, is cached by a refresh and
 * read back out of Room, and the next refresh replaces it.
 */
@RunWith(AndroidJUnit4::class)
class CollectivePageModeIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var repository: CollectiveRepositoryImpl

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
        env.close()
    }

    private fun list(pageMode: Int): MockResponse =
        OcsResponses.envelope("""{"collectives":[{"id":$ID,"name":"Wiki","pageMode":$pageMode}]}""")

    private suspend fun cachedPageMode(): CollectivePageMode =
        repository
            .observeCollectives()
            .first()
            .single()
            .pageMode

    @Test
    fun aRefreshedPageModeIsCached_andTheNextRefreshReplacesIt() =
        runTest {
            dispatcher.on(COLLECTIVES_PATH, list(pageMode = 1), method = "GET")
            assertTrue(repository.refresh() is ApiResult.Success)
            assertEquals(CollectivePageMode.Edit, cachedPageMode())

            dispatcher.on(COLLECTIVES_PATH, list(pageMode = 0), method = "GET")
            assertTrue(repository.refresh() is ApiResult.Success)
            assertEquals(CollectivePageMode.View, cachedPageMode())
        }

    private companion object {
        const val ID = IntegrationEnvironment.COLLECTIVE_ID
        const val COLLECTIVES_PATH = "v1.0/collectives"
    }
}
