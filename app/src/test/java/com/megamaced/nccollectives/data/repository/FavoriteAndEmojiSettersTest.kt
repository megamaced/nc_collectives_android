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
import io.mockk.coEvery
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
 * `toggleFavorite` and `setCollectiveEmoji` against each other: like
 * `setPageOrder` (see `SetPageOrderTest`), each writes optimistically and
 * rolls back to what it read, so two in flight at once could roll back
 * over each other. The favorites list is the sharp case: each toggle sends
 * and rolls back the whole list, so toggles on two *different* pages
 * overlap. The cached row is held in plain variables behind the DAO mock.
 */
class FavoriteAndEmojiSettersTest {
    private var cachedFavorites = "11,12"
    private var cachedEmoji: String? = null
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
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        coEvery { dao.getById(COLLECTIVE_ID) } answers {
            CollectiveDto(
                id = COLLECTIVE_ID,
                name = "Wiki",
                emoji = cachedEmoji,
                userFavoritePages = cachedFavorites.split(',').filter { it.isNotEmpty() }.map { it.toLong() },
            ).toEntity(now = 0L)
        }
        coEvery { dao.updateFavoritePagesCsv(COLLECTIVE_ID, any()) } answers { cachedFavorites = secondArg() }
        coEvery { dao.updateEmoji(COLLECTIVE_ID, any()) } answers { cachedEmoji = secondArg() }
    }

    /**
     * Codex's review case: unstar 11, and while that request is out unstar
     * 12, which succeeds; then 11's fails. Side by side, 12's toggle read
     * the list with 11 already dropped and sent `[]`, and 11's rollback then
     * put back `{11,12}`, 12 included, which the server had just dropped.
     * One at a time, 12's toggle starts from the rolled-back `{11,12}` and
     * sends `[11]`, which is what the cache and the server both hold.
     */
    @Test
    fun `a failed unstar cannot put back another page unstarred meanwhile`() =
        runTest {
            val firstRequest = CompletableDeferred<Unit>()
            coEvery { api.setFavoritePages(COLLECTIVE_ID, any()) } returns Unit
            coEvery { api.setFavoritePages(COLLECTIVE_ID, "[12]") } coAnswers { firstRequest.await() }

            val first = async { repository.toggleFavorite(COLLECTIVE_ID, 11, favorite = false) }
            runCurrent()
            val second = async { repository.toggleFavorite(COLLECTIVE_ID, 12, favorite = false) }
            runCurrent()
            firstRequest.completeExceptionally(IOException("timed out"))

            assertTrue(first.await() is ApiResult.NetworkError)
            assertEquals(ApiResult.Success(Unit), second.await())
            assertEquals("11", cachedFavorites)
            coVerifyOrder {
                api.setFavoritePages(COLLECTIVE_ID, "[12]")
                api.setFavoritePages(COLLECTIVE_ID, "[11]")
            }
        }

    @Test
    fun `a slow failed emoji cannot roll back over a later one`() =
        runTest {
            val firstRequest = CompletableDeferred<Unit>()
            coEvery { api.setCollectiveEmoji(COLLECTIVE_ID, "🍲") } coAnswers {
                firstRequest.await()
                mockk(relaxed = true)
            }
            coEvery { api.setCollectiveEmoji(COLLECTIVE_ID, "📚") } returns mockk(relaxed = true)

            val first = async { repository.setCollectiveEmoji(COLLECTIVE_ID, "🍲") }
            runCurrent()
            val second = async { repository.setCollectiveEmoji(COLLECTIVE_ID, "📚") }
            runCurrent()
            firstRequest.completeExceptionally(IOException("timed out"))

            assertTrue(first.await() is ApiResult.NetworkError)
            assertTrue(second.await() is ApiResult.Success)
            assertEquals("📚", cachedEmoji)
        }

    private companion object {
        const val COLLECTIVE_ID = 7L
    }
}
