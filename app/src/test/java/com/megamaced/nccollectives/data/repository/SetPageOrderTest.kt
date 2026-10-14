package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.dao.PageViewDao
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.domain.model.PageOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `CollectiveRepositoryImpl.setPageOrder`: an optimistic cache write that is
 * rolled back when the server refuses. The cached `userPageOrder` is held in
 * a plain variable behind the DAO mock, so each test can read what the tree
 * would end up showing.
 */
class SetPageOrderTest {
    private var cachedOrder = PageOrder.Custom.wire
    private val api = mockk<CollectivesApiService>()
    private val dao = mockk<CollectiveDao>()
    private val database = mockk<NcCollectivesDatabase>()

    private val repository =
        CollectiveRepositoryImpl(
            api = api,
            circlesApi = mockk<CirclesApiService>(),
            dao = dao,
            pageDao = mockk<PageDao>(),
            attachmentDao = mockk<AttachmentDao>(),
            editQueueDao = mockk<EditQueueDao>(),
            pageViewDao = mockk<PageViewDao>(),
            database = database,
            accountGeneration = AccountGeneration(),
        )

    init {
        // Room's transaction is a static extension; the writes it wraps are
        // what these tests are about, so it just runs them.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        coEvery { dao.getById(COLLECTIVE_ID) } answers {
            CollectiveDto(id = COLLECTIVE_ID, name = "Field Guide", userPageOrder = cachedOrder).toEntity(now = 0L)
        }
        coEvery { dao.updatePageOrder(COLLECTIVE_ID, any()) } answers { cachedOrder = secondArg() }
    }

    @Test
    fun `a refused change puts the previous order back`() =
        runTest {
            coEvery { api.setPageOrder(COLLECTIVE_ID, any()) } throws IOException("offline")

            val result = repository.setPageOrder(COLLECTIVE_ID, PageOrder.TitleAscending)

            assertTrue(result is ApiResult.NetworkError)
            assertEquals(PageOrder.Custom.wire, cachedOrder)
        }

    /**
     * Codex's review case. Two picks in quick succession, where the first
     * request fails only after the second has been made. Run concurrently,
     * the second succeeded and the first then "rolled back" to custom order
     * over it — cache `0 → 1 → 2 → 0` while the server held 2. Serialised,
     * the second pick starts after the first has been rolled back, and wins.
     */
    @Test
    fun `a slow failure cannot roll back over a later pick`() =
        runTest {
            val firstRequest = CompletableDeferred<Unit>()
            coEvery { api.setPageOrder(COLLECTIVE_ID, PageOrder.NewestFirst.wire) } coAnswers {
                firstRequest.await()
            }
            coEvery { api.setPageOrder(COLLECTIVE_ID, PageOrder.TitleAscending.wire) } returns Unit

            val first = async { repository.setPageOrder(COLLECTIVE_ID, PageOrder.NewestFirst) }
            runCurrent()
            val second = async { repository.setPageOrder(COLLECTIVE_ID, PageOrder.TitleAscending) }
            runCurrent()
            firstRequest.completeExceptionally(IOException("timed out"))

            assertTrue(first.await() is ApiResult.NetworkError)
            assertTrue(second.await() is ApiResult.Success)
            assertEquals(PageOrder.TitleAscending.wire, cachedOrder)
            // And the server heard the picks in the order they were made.
            coVerifyOrder {
                api.setPageOrder(COLLECTIVE_ID, PageOrder.NewestFirst.wire)
                api.setPageOrder(COLLECTIVE_ID, PageOrder.TitleAscending.wire)
            }
        }

    @Test
    fun `choosing the current order sends nothing`() =
        runTest {
            val result = repository.setPageOrder(COLLECTIVE_ID, PageOrder.Custom)

            assertTrue(result is ApiResult.Success)
            coVerify(exactly = 0) { api.setPageOrder(any(), any()) }
        }

    private companion object {
        const val COLLECTIVE_ID = 3L
    }
}
