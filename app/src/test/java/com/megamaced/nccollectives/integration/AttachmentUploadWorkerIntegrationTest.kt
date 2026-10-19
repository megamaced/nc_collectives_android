package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
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
import java.util.concurrent.atomic.AtomicReference

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
            val delete = dispatcher.requestsWithMethod("DELETE").single()
            assertEquals("the object that landed must be removed again, and only it", "\"up-1\"", delete.getHeader("If-Match"))
            assertNull(
                "and no row should be left claiming it exists",
                env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")),
            )
        }

    /**
     * Issue #56: a tombstone nothing proves landed — its upload never sent,
     * or never answered with success, or left by a version before 3.0 that
     * didn't record it. A DELETE by name could remove another client's file
     * there, so it is dropped without a request.
     */
    @Test
    fun aTombstoneNothingProvesLanded_isDroppedWithoutARequest() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", status = AttachmentEntity.STATUS_DELETING, attempts = 3)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")))
            assertTrue(
                "issue #23: the staged bytes go with the row",
                !env.stagedFile(12, "photo.jpg").exists(),
            )
            assertTrue("nothing sent, nothing deleted", dispatcher.requestsWithMethod("PUT").isEmpty())
            assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
        }

    /**
     * Issue #56: a tombstone whose upload was answered with success keeps
     * that answer's ETag, and its DELETE is conditional on it — on a later
     * run too, when the first try fails.
     */
    @Test
    fun aTombstoneWithProofItLanded_isDeletedConditionally_untilItGoes() =
        runTest {
            env.seedPage(id = 12)
            val key = env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", status = AttachmentEntity.STATUS_DELETING, etag = "up-1").id
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(503), OcsResponses.webDav(204), method = "DELETE")

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
            assertEquals(
                "up-1",
                env.db
                    .attachmentDao()
                    .getById(key)
                    ?.etag,
            )
            worker().doWork()

            assertEquals(listOf("\"up-1\"", "\"up-1\""), dispatcher.requestsWithMethod("DELETE").map { it.getHeader("If-Match") })
            assertNull(env.db.attachmentDao().getById(key))
        }

    /** Issue #56: only a tombstone is resolved — a listed file's ETag is no licence to delete it by name. */
    @Test
    fun resolvingADeletion_touchesOnlyATombstone() =
        runTest {
            env.seedPage(id = 12)
            val key = env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", status = AttachmentEntity.STATUS_REMOTE, etag = "listed").id

            assertTrue(env.attachmentRepository.resolveDeletion(12, "photo.jpg") is ApiResult.Success)

            assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
            assertEquals(
                AttachmentEntity.STATUS_REMOTE,
                env.db
                    .attachmentDao()
                    .getById(key)
                    ?.status,
            )
        }

    /** Issue #56: replaced since it landed, the file there is not this device's to remove. */
    @Test
    fun aTombstoneWhoseFileWasReplaced_isDroppedWithoutRemovingIt() =
        runTest {
            env.seedPage(id = 12)
            val key = env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", status = AttachmentEntity.STATUS_DELETING, etag = "up-1").id
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(412), method = "DELETE")

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)

            assertEquals(1, dispatcher.requestsWithMethod("DELETE").size)
            assertNull(env.db.attachmentDao().getById(key))
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
    fun aFailureAnsweredAfterASwitch_leavesTheNextAccountsRowAlone() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", attempts = 0)
            val key = AttachmentEntity.key(12, "photo.jpg")
            dispatcher
                .on("/remote.php/dav", OcsResponses.webDav(500), method = "PUT")
                .whileInFlight("/remote.php/dav") {
                    if (it.method == "PUT") {
                        env.accountGeneration.invalidate()
                        runBlocking {
                            env.db.attachmentDao().clear()
                            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", bytes = "B".toByteArray(), attempts = 4)
                        }
                        env.accountGeneration.settle()
                    }
                }

            val result = worker().doWork()

            assertTrue("abandoned, not retried: $result", result is ListenableWorker.Result.Success)
            val row = env.db.attachmentDao().getById(key)
            assertEquals(AttachmentEntity.STATUS_PENDING, row?.status)
            assertEquals("the next account's row, untouched", 4, row?.attempts)
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

    /**
     * Issue #63. Two uploads queued; while the first is on the wire the user
     * cancels the second. The worker read the queue before the cancel, and
     * must not send what the user took back.
     */
    @Test
    fun anUploadCancelledWhileAnotherIsSent_isNeverSent() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "a.jpg")
            env.seedStagedUpload(pageId = 12, fileName = "b.jpg")
            val cancelled = cancelTheOtherDuringTheFirstPut()

            worker().doWork()

            val puts = dispatcher.requestsWithMethod("PUT").map { it.path!!.substringAfterLast('/') }
            assertEquals("only the upload still wanted was sent", 1, puts.size)
            assertTrue(cancelled.get() !in puts)
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, cancelled.get())))
            assertTrue("its staged bytes go with it", !env.stagedFile(12, cancelled.get()).exists())
            assertTrue(
                "issue #56: never sent, so nothing to delete on the server",
                dispatcher.requestsWithMethod("DELETE").isEmpty(),
            )
        }

    /**
     * Issue #63 for an upload tried before, whose bytes may have landed with
     * the answer lost: it is dropped, not sent again — and, issue #56, not
     * deleted by name either, since nothing proves the file there is ours.
     */
    @Test
    fun anUploadTriedBeforeAndCancelledWhileAnotherIsSent_isDroppedNotSentOrDeleted() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "a.jpg", attempts = 1)
            env.seedStagedUpload(pageId = 12, fileName = "b.jpg", attempts = 1)
            val cancelled = cancelTheOtherDuringTheFirstPut()

            worker().doWork()

            val puts = dispatcher.requestsWithMethod("PUT").map { it.path!!.substringAfterLast('/') }
            assertTrue(cancelled.get() !in puts)
            assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, cancelled.get())))
        }

    /**
     * Issue #63: each row as it is when its turn comes. Cancelled and added
     * again while another upload was sent, it is a new upload with a fresh
     * budget, not the old one's last attempt.
     */
    @Test
    fun anUploadCancelledAndAddedAgainWhileAnotherIsSent_startsAFreshBudget() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "a.jpg")
            val b = env.seedStagedUpload(pageId = 12, fileName = "b.jpg", attempts = 9)
            // Queued after a.jpg, so it is a.jpg's PUT it changes during.
            env.db.attachmentDao().upsert(b.copy(lastSyncedAt = 1))
            dispatcher
                .on("/.attachments.12/a.jpg", OcsResponses.webDav(201, etag = "\"a\""), method = "PUT")
                .on("/.attachments.12/b.jpg", OcsResponses.webDav(503), method = "PUT")
                .whileInFlight("/.attachments.12/a.jpg") {
                    if (it.method == "PUT") {
                        runBlocking {
                            assertTrue(env.attachmentRepository.delete(12, "b.jpg") is ApiResult.Success)
                            env.seedStagedUpload(pageId = 12, fileName = "b.jpg")
                        }
                    }
                }

            worker().doWork()

            val again = env.db.attachmentDao().getById(AttachmentEntity.key(12, "b.jpg"))
            assertEquals("one attempt of its own, so it waits for another", AttachmentEntity.STATUS_PENDING, again?.status)
            assertEquals(1, again?.attempts)
        }

    /**
     * Issue #63: a failed attempt doesn't put a cancelled upload back in the
     * queue, or settle it as failed. This was its last attempt, so only the
     * tombstone is left to ask for another run.
     */
    @Test
    fun aFailedUploadCancelledWhileItWasOut_keepsItsTombstone() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", attempts = 9)
            val key = AttachmentEntity.key(12, "photo.jpg")
            dispatcher
                .on("/remote.php/dav", OcsResponses.webDav(500), method = "PUT")
                .whileInFlight("/.attachments.12/photo.jpg") { if (it.method == "PUT") runBlocking { tombstone(key) } }

            val result = worker().doWork()

            assertTrue("the tombstone is another run's to resolve", result is ListenableWorker.Result.Retry)
            assertEquals(
                AttachmentEntity.STATUS_DELETING,
                env.db
                    .attachmentDao()
                    .getById(key)
                    ?.status,
            )
        }

    /**
     * Issue #63 meeting #24: cancelled while its upload was out, and the name
     * turned out to be taken. Nothing of it landed, so it is dropped — not
     * re-queued under a new name, and not removed by a DELETE that would hit
     * the other client's file at that name.
     */
    @Test
    fun anUploadCancelledWhileItsNameWasTaken_isDroppedWithoutTouchingTheServer() =
        runTest {
            env.seedPage(id = 12, bodyMd = "![photo.jpg](photo.jpg)", bodyEtag = "body-1")
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            val key = AttachmentEntity.key(12, "photo.jpg")
            dispatcher
                .on("/.attachments.12/photo.jpg", OcsResponses.webDav(412), method = "PUT")
                .on("/.attachments.12", OcsResponses.webDav(207).setBody(EMPTY_MULTISTATUS), method = "PROPFIND")
                .whileInFlight("/.attachments.12/photo.jpg") { if (it.method == "PUT") runBlocking { tombstone(key) } }

            worker().doWork()

            assertNull(env.db.attachmentDao().getById(key))
            assertNull(env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo-1.jpg")))
            assertTrue(!env.stagedFile(12, "photo.jpg").exists())
            assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
            assertEquals(1, dispatcher.requestsWithMethod("PUT").size)
        }

    /**
     * Issue #63 at the source: claiming and settling are conditional, so a
     * row cancelled between the worker reading it and claiming it is neither
     * sent nor put back in the queue.
     */
    @Test
    fun aCancelledRowCannotBeClaimedOrSettled() =
        runTest {
            env.seedPage(id = 12)
            val dao = env.db.attachmentDao()
            val waiting = env.seedStagedUpload(pageId = 12, fileName = "a.jpg").id
            val cancelled = env.seedStagedUpload(pageId = 12, fileName = "b.jpg", status = AttachmentEntity.STATUS_DELETING).id

            assertEquals(1, dao.markUploading(waiting))
            assertEquals(0, dao.markUploading(cancelled))
            assertEquals(0, dao.setStatusUnlessCancelled(cancelled, AttachmentEntity.STATUS_PENDING))
            assertEquals(0, dao.markUploaded(cancelled, "image/jpeg", 6, "up", 1))
            assertEquals(AttachmentEntity.STATUS_DELETING, dao.getById(cancelled)?.status)
            assertEquals("never claimed, so no attempt spent", 0, dao.getById(cancelled)?.attempts)
            assertEquals("a worker holds it", 0, dao.deleteUnclaimed(waiting))
            // Issue #56: proof of a landing goes only on a tombstone, and a
            // tombstone with it is not dropped as unproven.
            dao.confirmLanded(waiting, "up")
            assertNull(dao.getById(waiting)?.etag)
            dao.confirmLanded(cancelled, "up")
            assertEquals(0, dao.deleteUnconfirmedTombstone(cancelled))
        }

    /**
     * Issue #56: an upload cancelled after the worker claimed it, while it
     * was still making sure the attachments folder exists. No PUT went out,
     * so nothing is deleted on the server — whose DELETE, by name, would
     * remove another client's file there — whether the folder check failed
     * or not.
     */
    @Test
    fun anUploadCancelledBeforeItsPutWhenTheFolderCheckFails_isDroppedWithoutADelete() =
        runTest { cancelledDuringTheFolderCheck(OcsResponses.webDav(500)) }

    @Test
    fun anUploadCancelledBeforeItsPutWhenTheFolderCheckSucceeds_isNeitherSentNorDeleted() =
        runTest { cancelledDuringTheFolderCheck(OcsResponses.webDav(201)) }

    private suspend fun cancelledDuringTheFolderCheck(answer: okhttp3.mockwebserver.MockResponse) {
        env.seedPage(id = 12)
        env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
        val key = AttachmentEntity.key(12, "photo.jpg")
        dispatcher
            .on("/remote.php/dav", answer, method = "MKCOL")
            .on("/remote.php/dav", OcsResponses.webDav(204), method = "DELETE")
            .whileInFlight("/remote.php/dav") {
                if (it.method == "MKCOL") runBlocking { assertTrue(env.attachmentRepository.delete(12, "photo.jpg") is ApiResult.Success) }
            }

        worker().doWork()
        assertEquals(
            "claimed, so a tombstone",
            AttachmentEntity.STATUS_DELETING,
            env.db
                .attachmentDao()
                .getById(key)
                ?.status,
        )
        worker().doWork()

        assertTrue(dispatcher.requestsWithMethod("PUT").isEmpty())
        assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
        assertNull(env.db.attachmentDao().getById(key))
        assertTrue(!env.stagedFile(12, "photo.jpg").exists())
    }

    /**
     * Issue #56: an upload no worker holds is dropped on cancel whatever its
     * history — failed after every attempt, or put back by Retry. An earlier
     * attempt may have landed with its answer lost, or been refused because
     * the name was another client's; nothing here can tell which, so nothing
     * is deleted by name. Bytes that did land show up on the next listing.
     */
    @Test
    fun anUploadNoWorkerHolds_isDroppedOnCancel_whateverItsHistory() =
        runTest {
            env.seedPage(id = 12)
            val failed = env.seedStagedUpload(pageId = 12, fileName = "failed.jpg", status = AttachmentEntity.STATUS_FAILED, attempts = 10)
            val retried = env.seedStagedUpload(
                pageId = 12,
                fileName = "retried.jpg",
                status = AttachmentEntity.STATUS_FAILED,
                attempts = 10,
            )

            assertTrue(env.attachmentRepository.retryUpload(12, "retried.jpg") is ApiResult.Success)
            assertTrue(env.attachmentRepository.delete(12, "retried.jpg") is ApiResult.Success)
            assertTrue(env.attachmentRepository.delete(12, "failed.jpg") is ApiResult.Success)

            assertNull(env.db.attachmentDao().getById(failed.id))
            assertNull(env.db.attachmentDao().getById(retried.id))
            assertTrue(!env.stagedFile(12, "failed.jpg").exists())
            assertTrue(!env.stagedFile(12, "retried.jpg").exists())
            assertTrue(dispatcher.requestsWithMethod("DELETE").isEmpty())
        }

    /**
     * Issue #63: the upload landed, and the user cancels just as the worker
     * records it. Whichever is first, the cancellation stands — the record is
     * conditional, and the worker removes what landed instead.
     */
    @Test
    fun aCancellationAsTheUploadIsRecorded_removesWhatLandedRatherThanKeepingIt() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            val key = AttachmentEntity.key(12, "photo.jpg")
            dispatcher
                .on("/remote.php/dav", OcsResponses.webDav(201, etag = "\"up\""), method = "PUT")
                .on("/remote.php/dav", OcsResponses.webDav(204), method = "DELETE")
            val real = env.db.attachmentDao()
            val cancelFirst =
                object : AttachmentDao by real {
                    override suspend fun markUploaded(
                        id: String,
                        contentType: String?,
                        size: Long,
                        etag: String?,
                        at: Long,
                    ): Int {
                        tombstone(key)
                        return real.markUploaded(id, contentType, size, etag, at)
                    }
                }

            worker(cancelFirst).doWork()

            assertEquals("\"up\"", dispatcher.requestsWithMethod("DELETE").single().getHeader("If-Match"))
            assertNull(real.getById(key))
        }

    /**
     * On the first upload's PUT, cancel the other queued one through the
     * repository, as the attachments screen does. Returns which one that was.
     */
    private fun cancelTheOtherDuringTheFirstPut(): AtomicReference<String> {
        val cancelled = AtomicReference<String>()
        dispatcher
            .on("/remote.php/dav", OcsResponses.webDav(201, etag = "\"up\""), method = "PUT")
            .whileInFlight("/.attachments.12/") { request ->
                if (request.method != "PUT" || cancelled.get() != null) return@whileInFlight
                val other = if (request.path!!.endsWith("a.jpg")) "b.jpg" else "a.jpg"
                cancelled.set(other)
                runBlocking {
                    assertTrue(env.attachmentRepository.delete(12, other) is ApiResult.Success)
                    assertNull("dropped outright", env.db.attachmentDao().getById(AttachmentEntity.key(12, other)))
                }
            }
        return cancelled
    }

    private suspend fun tombstone(key: String) {
        env.db.attachmentDao().setStatus(key, AttachmentEntity.STATUS_DELETING)
    }

    private fun worker(attachmentDao: AttachmentDao = env.db.attachmentDao()): AttachmentUploadWorker =
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
                            attachmentDao = attachmentDao,
                            bodyService = env.bodyService,
                            attachmentRepository = env.attachmentRepository,
                            pageRepository = env.pageRepository,
                            database = env.db,
                            accountGeneration = env.accountGeneration,
                        )
                },
            ).build()

    private companion object {
        val EMPTY_MULTISTATUS =
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>"""
    }
}
