package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.sync.AttachmentUploadWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AttachmentUploadWorker` driven end to end against a real Nextcloud-shaped
 * server, with real Room rows and real staged bytes on disk.
 *
 * Five closed issues meet in this one loop — #23 (a `FAILED` row keeps its
 * bytes), #24 (`If-None-Match: *` refuses to overwrite), #30 (the row owns
 * its retry budget, not the WorkRequest), #35 (a delete during an upload
 * leaves a tombstone the worker resolves) and #40 (the body has to follow a
 * collision rename) — and every one of them is a decision made from state
 * another decision just wrote. The unit suite pins each decision in
 * isolation; nothing until now checked that the sequence they form is the
 * one intended.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentUploadWorkerIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        // MKCOL on `.attachments.<pageId>`: 405 is "already there", which is
        // the normal case and a success as far as `ensureCollection` is
        // concerned.
        dispatcher.on("/remote.php/dav", OcsResponses.webDav(405), method = "MKCOL")
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun successfulUpload_recordsTheEtagAndCollectsTheStagedBytes() =
        runTest {
            env.seedPage(id = 12, bodyMd = "![photo.jpg](photo.jpg)")
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(201, etag = "\"up-1\""), method = "PUT")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            val row = env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg"))
            assertEquals(AttachmentEntity.STATUS_REMOTE, row?.status)
            assertEquals("up-1", row?.etag)
            assertNull("a REMOTE row must not still claim local bytes", row?.localUriString)
            assertTrue("the staging copy is redundant once the bytes are up", !env.stagedFile(12, "photo.jpg").exists())

            val put = dispatcher.requestsWithMethod("PUT").single()
            assertEquals("issue #24: an upload must never overwrite blind", "*", put.getHeader("If-None-Match"))
            assertEquals(
                "/remote.php/dav/files/alice/.Collectives/Wiki/.attachments.12/photo.jpg",
                put.path,
            )
        }

    @Test
    fun uploadRefusedByIfNoneMatch_renamesRequeuesAndRepointsTheBody() =
        runTest {
            env.seedPage(
                id = 12,
                bodyMd = "Shared from Chrome\n\n![photo.jpg](photo.jpg)",
                bodyEtag = "body-1",
            )
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", bytes = "mine".toByteArray())
            dispatcher
                // First PUT is the attachment and is refused; the second is the
                // body save that follows the rename. Routed by path so the two
                // cannot be confused for one another — on the extension, because
                // the page's own path arrives percent-encoded (`/Page%2012.md`).
                .on("/.attachments.12/photo.jpg", OcsResponses.webDav(412), method = "PUT")
                .on(".md", OcsResponses.webDav(204, etag = "\"body-2\""), method = "PUT")
                .on("/.attachments.12", OcsResponses.webDav(207).setBody(EMPTY_MULTISTATUS), method = "PROPFIND")

            val result = worker().doWork()

            assertTrue("a rename means there is more to do", result is ListenableWorker.Result.Retry)
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")))
            val requeued = env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo-1.jpg"))
            assertEquals(AttachmentEntity.STATUS_PENDING, requeued?.status)
            assertEquals("mine", env.stagedFile(12, "photo-1.jpg").readText())

            assertEquals(
                "issue #40: the body must not be left pointing at the other client's file",
                "Shared from Chrome\n\n![photo.jpg](photo-1.jpg)",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.bodyMd,
            )
        }

    @Test
    fun uploadRefusedWithTheBodyOffline_stillMovesTheUploadAndQueuesTheReference() =
        runTest {
            // The reference is recoverable — the queue flushes later — but the
            // bytes are not, so the rename must not be held up by a body save
            // that cannot land.
            env.seedPage(id = 12, bodyMd = "![photo.jpg](photo.jpg)", bodyEtag = "body-1")
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            dispatcher
                .on("/.attachments.12/photo.jpg", OcsResponses.webDav(412), method = "PUT")
                .on("/.attachments.12", OcsResponses.webDav(207).setBody(EMPTY_MULTISTATUS), method = "PROPFIND")
                .on(".md", OcsResponses.webDav(500), method = "PUT")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            assertNotNull(
                "the upload has moved regardless of the body",
                env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo-1.jpg")),
            )
            assertTrue(env.stagedFile(12, "photo-1.jpg").exists())
        }

    @Test
    fun uploadThatLandsAfterTheUserDeletedIt_removesWhatLanded() =
        runTest {
            // Issue #35. The row is a tombstone by the time the PUT returns, so
            // the worker's success arm has to undo its own upload rather than
            // record it — otherwise the next PROPFIND lists the file again and an
            // attachment the UI reported as deleted comes back.
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            dispatcher
                .on("/remote.php/dav", OcsResponses.webDav(201, etag = "\"up-1\""), method = "PUT")
                .on("/remote.php/dav", OcsResponses.webDav(204), method = "DELETE")
                .whileInFlight("/.attachments.12/photo.jpg") {
                    // Lands while the PUT is on the wire: the user tapped delete
                    // and the UI has already told them it is gone.
                    if (it.method == "PUT") {
                        // The hook runs on the server's thread with the upload
                        // still blocked on its response, which is the whole
                        // point: the delete has to land *during* the PUT.
                        runBlocking { tombstone(AttachmentEntity.key(12, "photo.jpg")) }
                    }
                }

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(
                "the object that landed must be removed again",
                1,
                dispatcher.requestsWithMethod("DELETE").size,
            )
            assertNull(
                "and no row should be left claiming it exists",
                env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")),
            )
        }

    @Test
    fun tombstoneWithNothingUploaded_isResolvedAndDropped() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(
                pageId = 12,
                fileName = "photo.jpg",
                status = AttachmentEntity.STATUS_DELETING,
            )
            // 404: the bytes never made it, which is the outcome asked for.
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(404), method = "DELETE")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")))
            assertTrue(
                "issue #23: the staged bytes go with the row",
                !env.stagedFile(12, "photo.jpg").exists(),
            )
            assertTrue("no upload should have been attempted", dispatcher.requestsWithMethod("PUT").isEmpty())
        }

    @Test
    fun repeatedServerErrors_spendTheRowsOwnBudgetAndThenSettleWithTheBytesKept() =
        runTest {
            // Issue #30: the budget belongs to the row, so a row already near its
            // limit settles on this run rather than inheriting a fresh allowance
            // from a new WorkRequest. Issue #23: settling keeps the bytes, which
            // is what makes the grid's Retry button able to do anything.
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", attempts = 9)
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(500), method = "PUT")

            val result = worker().doWork()

            assertTrue("the budget is spent, so nothing is left to retry", result is ListenableWorker.Result.Success)
            val row = env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg"))
            assertEquals(AttachmentEntity.STATUS_FAILED, row?.status)
            assertEquals(10, row?.attempts)
            assertTrue("the bytes stay so a retry has something to send", env.stagedFile(12, "photo.jpg").exists())
            assertNotNull(row?.localUriString)
        }

    @Test
    fun aRowWithBudgetLeft_asksForAnotherRun() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", attempts = 0)
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(500), method = "PUT")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            val row = env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg"))
            assertEquals(AttachmentEntity.STATUS_PENDING, row?.status)
            assertEquals("the attempt is spent when it is claimed, not when it fails", 1, row?.attempts)
        }

    private suspend fun tombstone(key: String) {
        env.db.attachmentDao().setStatus(key, AttachmentEntity.STATUS_DELETING)
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
                            appContext = appContext,
                            params = workerParameters,
                            pageDao = env.db.pageDao(),
                            attachmentDao = env.db.attachmentDao(),
                            bodyService = env.bodyService,
                            attachmentRepository = env.attachmentRepository,
                            pageRepository = env.pageRepository,
                            accountGeneration = env.accountGeneration,
                        )
                },
            ).build()

    private companion object {
        val EMPTY_MULTISTATUS =
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>"""
    }
}
