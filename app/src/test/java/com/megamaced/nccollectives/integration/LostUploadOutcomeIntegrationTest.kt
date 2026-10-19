package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.auth.AccountBoundCallFactory
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import com.megamaced.nccollectives.sync.AttachmentUploadWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Issue #56, where the answer to an upload is lost. The PUT carries
 * `If-None-Match: *`, so a name already taken on the server is refused with
 * a 412 — and when that 412 never arrives, nothing tells this device whether
 * the file at that name is its upload or another client's. Cancelling it
 * then must not delete by name.
 *
 * The connection drops after the PUT is received, over HTTP/1.1 with
 * OkHttp's retry off, so the request is not silently sent again.
 */
@RunWith(AndroidJUnit4::class)
class LostUploadOutcomeIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var server: CancellingQueue
    private lateinit var repository: AttachmentRepositoryImpl
    private lateinit var bodyService: PageBodyService

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        env.server.protocols = listOf(Protocol.HTTP_1_1)
        server = CancellingQueue()
        env.server.dispatcher = server
        val client = env.client
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .build()
        bodyService = PageBodyService(AccountBoundCallFactory(client, env.accountGeneration), env.tokenStore)
        repository = AttachmentRepositoryImpl(
            env.context,
            env.api,
            env.db.pageDao(),
            env.db.attachmentDao(),
            bodyService,
            env.syncScheduler,
            env.db,
            env.accountGeneration,
        )
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun anUploadWhoseAnswerWasLost_cancelledAfterwards_deletesNothing() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(12, "photo.jpg")
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(412).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
            assertEquals("MKCOL", env.server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertEquals("*", env.server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("If-None-Match"))
            assertTrue(repository.delete(12, "photo.jpg") is ApiResult.Success)
            worker().doWork()

            assertNull("nothing sent after the lost answer", env.server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")))
        }

    @Test
    fun anUploadCancelledWhileItsAnswerIsLost_deletesNothing() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(12, "photo.jpg")
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(412).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.onPut = { runBlocking { assertTrue(repository.delete(12, "photo.jpg") is ApiResult.Success) } }

            worker().doWork()
            assertEquals(
                "claimed when cancelled, so a tombstone",
                AttachmentEntity.STATUS_DELETING,
                env.db
                    .attachmentDao()
                    .getById(AttachmentEntity.key(12, "photo.jpg"))
                    ?.status,
            )
            env.server.takeRequest(1, TimeUnit.SECONDS)
            env.server.takeRequest(1, TimeUnit.SECONDS)
            worker().doWork()

            assertNull("nothing sent after the lost answer", env.server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")))
        }

    private fun worker(): AttachmentUploadWorker =
        TestListenableWorkerBuilder<AttachmentUploadWorker>(env.context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        AttachmentUploadWorker(
                            appContext,
                            workerParameters,
                            env.db.pageDao(),
                            env.db.attachmentDao(),
                            bodyService,
                            repository,
                            env.pageRepository,
                            env.db,
                            env.accountGeneration,
                        )
                },
            ).build()

    /**
     * Answers in order, as [okhttp3.mockwebserver.QueueDispatcher] does —
     * [peek] included, which is where MockWebServer looks for a queued
     * socket policy — and runs [onPut] when a PUT arrives, before its answer.
     */
    private class CancellingQueue : Dispatcher() {
        private val answers = LinkedBlockingQueue<MockResponse>()

        @Volatile var onPut: (() -> Unit)? = null

        fun enqueue(response: MockResponse) {
            answers.add(response)
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "PUT") onPut?.invoke()
            return answers.poll() ?: MockResponse().setResponseCode(501).setBody("No answer queued for ${request.method}")
        }

        override fun peek(): MockResponse = answers.peek() ?: super.peek()
    }
}
