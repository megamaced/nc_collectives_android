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
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.domain.model.NotifyLevel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `CollectiveRepositoryImpl.setNotifyLevel`: the same optimistic write and
 * rollback as the page order, under the same lock, and a refresh carries
 * the server's level into the cache.
 */
class SetNotifyLevelTest {
    private var cachedNotify: Int? = NotifyLevel.Mentions.wire
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
            CollectiveDto(id = COLLECTIVE_ID, name = "Field Guide", userNotify = cachedNotify).toEntity(now = 0L)
        }
        coEvery { dao.updateNotify(COLLECTIVE_ID, any()) } answers { cachedNotify = secondArg() }
    }

    @Test
    fun `an accepted change is cached`() =
        runTest {
            coEvery { api.setNotify(COLLECTIVE_ID, NotifyLevel.All.wire) } returns Unit

            assertTrue(repository.setNotifyLevel(COLLECTIVE_ID, NotifyLevel.All) is ApiResult.Success)
            assertEquals(NotifyLevel.All.wire, cachedNotify)
        }

    @Test
    fun `a refused change puts the previous level back`() =
        runTest {
            coEvery { api.setNotify(COLLECTIVE_ID, any()) } throws IOException("offline")

            val result = repository.setNotifyLevel(COLLECTIVE_ID, NotifyLevel.Off)

            assertTrue(result is ApiResult.NetworkError)
            assertEquals(NotifyLevel.Mentions.wire, cachedNotify)
        }

    @Test
    fun `a slow failure cannot roll back over a later pick`() =
        runTest {
            val firstRequest = CompletableDeferred<Unit>()
            coEvery { api.setNotify(COLLECTIVE_ID, NotifyLevel.Off.wire) } coAnswers { firstRequest.await() }
            coEvery { api.setNotify(COLLECTIVE_ID, NotifyLevel.All.wire) } returns Unit

            val first = async { repository.setNotifyLevel(COLLECTIVE_ID, NotifyLevel.Off) }
            runCurrent()
            val second = async { repository.setNotifyLevel(COLLECTIVE_ID, NotifyLevel.All) }
            runCurrent()
            firstRequest.completeExceptionally(IOException("timed out"))

            assertTrue(first.await() is ApiResult.NetworkError)
            assertTrue(second.await() is ApiResult.Success)
            assertEquals(NotifyLevel.All.wire, cachedNotify)
        }

    @Test
    fun `choosing the current level sends nothing`() =
        runTest {
            assertTrue(repository.setNotifyLevel(COLLECTIVE_ID, NotifyLevel.Mentions) is ApiResult.Success)
            coVerify(exactly = 0) { api.setNotify(any(), any()) }
        }

    @Test
    fun `a server without the setting leaves it unreported, not off`() {
        val entity = CollectiveDto(id = COLLECTIVE_ID, name = "Field Guide").toEntity(now = 0L)

        assertNull(entity.userNotify)
        assertNull(entity.toDomain().notifyLevel)
        assertEquals(
            NotifyLevel.Off,
            CollectiveDto(id = COLLECTIVE_ID, name = "Field Guide", userNotify = 0).toEntity(now = 0L).toDomain().notifyLevel,
        )
    }

    private companion object {
        const val COLLECTIVE_ID = 3L
    }
}
