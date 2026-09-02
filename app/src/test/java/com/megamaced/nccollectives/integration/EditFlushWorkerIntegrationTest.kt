package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.sync.EditFlushWorker
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
 * `EditFlushWorker` draining the queue against a real server.
 *
 * The queue is the local source of truth for a page's body (issue #18), and
 * everything that reads or settles it — the editor, the append path, the
 * foreground save, this worker — reaches the same rows by a different route.
 * Issue #29 was precisely a disagreement between two of those routes about
 * which precondition a row was written against, and it survived a full unit
 * suite because each route was individually right.
 */
@RunWith(AndroidJUnit4::class)
class EditFlushWorkerIntegrationTest {
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
    fun aQueuedEditThatLands_becomesTheCachedBodyAndLeavesTheQueueEmpty() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "my offline edit", baseEtag = "etag-1"))
            // Two requests per row, not one: B-61's preflight GET reads the
            // server's current etag before the PUT, so a flush that agrees with
            // the server never sends a conditional write it knows will fail.
            dispatcher
                .on(".md", serverBody("server body", "etag-1"), method = "GET")
                .on(".md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")

            val result = worker().doWork()

            assertTrue("expected success, was $result", result is ListenableWorker.Result.Success)
            val row = env.db.pageDao().getById(12)
            assertEquals("my offline edit", row?.bodyMd)
            assertEquals("etag-2", row?.bodyEtag)
            assertNull("the row is settled, so nothing should remain queued", env.db.editQueueDao().forPage(12))

            val put = dispatcher.requestsWithMethod("PUT").single()
            assertEquals(
                "the flush must send the etag the *queue row* was written against",
                "\"etag-1\"",
                put.getHeader("If-Match"),
            )
            assertEquals("my offline edit", put.body.readUtf8())
        }

    @Test
    fun aQueuedEditThatLosesTheEtagRace_parksAsAConflictWithTheTextKept() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "my offline edit", baseEtag = "etag-1"))
            dispatcher
                // The preflight agrees, so the PUT goes out — and *then* loses
                // the race, which is the arm worth testing.
                .on(".md", serverBody("server body", "etag-1"), method = "GET")
                .on(".md", OcsResponses.webDav(412), method = "PUT")

            val result = worker().doWork()

            assertTrue("a conflict is settled, not retried", result is ListenableWorker.Result.Success)
            val row = env.db.pageDao().getById(12)
            assertEquals("the user's text must survive as a draft", "my offline edit", row?.draftBodyMd)
            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
            // Deliberately pinning what the worker does, which is *not* what
            // `PageRepositoryImpl.saveBody`'s conflict branch does: that one
            // refetches, on the stated grounds that leaving `bodyEtag` at a value
            // the server has already rejected guarantees the next save is another
            // 412, and that the user needs to see what they are conflicting with.
            // The worker parks the draft and stops, so the banner offers a
            // comparison against a cached body that is no longer the server's.
            // Not a regression and not one of the audited issues — recorded here
            // so the asymmetry is visible rather than assumed intentional.
            assertEquals("server body", row?.bodyMd)
            assertEquals("etag-1", row?.bodyEtag)
            assertNull(
                "a CONFLICTED row is not a pending body — the banner owns it now",
                env.db.editQueueDao().pendingBody(12),
            )
            assertEquals(1, dispatcher.requestsWithMethod("PUT").size)
        }

    @Test
    fun aPreflightThatSeesTheServerHasMovedOn_parksWithoutWritingAnything() =
        runTest {
            // B-61 again: the cheap half. If the etag has already moved there is
            // nothing to be gained by sending the PUT, and something to lose —
            // `saveBody` treats a null precondition as a blind overwrite.
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "my offline edit", baseEtag = "etag-1"))
            dispatcher.on(".md", serverBody("someone else's body", "etag-9"), method = "GET")

            worker().doWork()

            assertTrue("no write should have been attempted", dispatcher.requestsWithMethod("PUT").isEmpty())
            assertEquals(
                "my offline edit",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
        }

    @Test
    fun aForceWriteThatLosesTheRace_surfacesTheConflictRatherThanOverwriting() =
        runTest {
            // B-46: "Replace with my draft" can itself race another writer. The
            // second race is not the user's decision, so it goes back to them.
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(
                queued(pageId = 12, body = "my draft", baseEtag = null).copy(forceWrite = true),
            )
            dispatcher.on(".md", OcsResponses.webDav(412), method = "PUT")

            worker().doWork()

            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
            val put = dispatcher.requestsWithMethod("PUT").single()
            assertNull("a force-write sends no precondition", put.getHeader("If-Match"))
        }

    @Test
    fun aRowWithBudgetLeft_asksForAnotherRunAndKeepsTheText() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "my offline edit", baseEtag = "etag-1"))
            dispatcher
                .on(".md", serverBody("server body", "etag-1"), method = "GET")
                .on(".md", OcsResponses.webDav(500), method = "PUT")

            val result = worker().doWork()

            assertTrue("expected retry, was $result", result is ListenableWorker.Result.Retry)
            val row = env.db.editQueueDao().forPage(12)
            assertEquals("my offline edit", row?.newBodyMd)
            assertEquals("issue #30: the attempt is spent when the row is claimed", 1, row?.attempts)
        }

    @Test
    fun aRowOutOfBudget_settlesAsAConflictSoTheTextIsReachable() =
        runTest {
            // Issue #30's trade-off, made concrete: capping the retries means the
            // edit stops being invisibly in-flight forever and starts being
            // something the user can see and act on.
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(
                queued(pageId = 12, body = "my offline edit", baseEtag = "etag-1").copy(attempts = 9),
            )
            dispatcher
                .on(".md", serverBody("server body", "etag-1"), method = "GET")
                .on(".md", OcsResponses.webDav(500), method = "PUT")

            val result = worker().doWork()

            assertTrue("out of budget means settled, not retried", result is ListenableWorker.Result.Success)
            assertEquals(
                "my offline edit",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
        }

    @Test
    fun aFlushWhoseResponseLandsAfterAWipe_writesNothing() =
        runTest {
            // Issue #20 on the path that cannot be cancelled: `recordPutOutcome`
            // runs `NonCancellable` on purpose, so the generation guard inside the
            // transaction is the only thing standing between one account's edit
            // and another account's cache.
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "account A's edit", baseEtag = "etag-1"))
            dispatcher
                .on(".md", serverBody("server body", "etag-1"), method = "GET")
                .on(".md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")
                // On the PUT, not the preflight: the point is a reply the worker
                // is committed to recording — `recordPutOutcome` is
                // `NonCancellable` — arriving after the account has changed.
                .whileInFlight(".md") { if (it.method == "PUT") env.accountGeneration.invalidate() }

            worker().doWork()

            assertEquals(
                "the outgoing account's edit must not become the cached body",
                "server body",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.bodyMd,
            )
        }

    @Test
    fun anEmptyQueue_doesNothingAtAll() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(dispatcher.requests.isEmpty())
        }

    @Test
    fun oneUnflushableRow_doesNotStopTheRest() =
        runTest {
            // B-63's sibling on the edit side: the queue is drained per row, so a
            // page the server refuses must not hold up a page it would accept.
            env.seedPage(id = 12, title = "Refused", bodyMd = "a", bodyEtag = "etag-1")
            env.seedPage(id = 13, title = "Accepted", bodyMd = "b", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "first", baseEtag = "etag-1"))
            env.db.editQueueDao().upsert(queued(pageId = 13, body = "second", baseEtag = "etag-1"))
            dispatcher
                .on(".md", serverBody("whatever", "etag-1"), method = "GET")
                .on("Refused.md", OcsResponses.webDav(500), method = "PUT")
                .on("Accepted.md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")

            worker().doWork()

            assertEquals(
                "second",
                env.db
                    .pageDao()
                    .getById(13)
                    ?.bodyMd,
            )
            assertNull(env.db.editQueueDao().forPage(13))
            assertNotNull("and the refused row is still queued", env.db.editQueueDao().forPage(12))
        }

    /** A WebDAV `GET` of a page body: what the flush preflight reads. */
    private fun serverBody(
        markdown: String,
        etag: String,
    ) = OcsResponses.webDav(200, etag = "\"$etag\"").setBody(markdown)

    private fun queued(
        pageId: Long,
        body: String,
        baseEtag: String?,
    ) = EditQueueEntity(
        pageId = pageId,
        baseEtag = baseEtag,
        newBodyMd = body,
        queuedAt = 1L,
        status = "PENDING",
    )

    private fun worker(): EditFlushWorker =
        TestListenableWorkerBuilder<EditFlushWorker>(env.context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        EditFlushWorker(
                            appContext = appContext,
                            params = workerParameters,
                            pageDao = env.db.pageDao(),
                            editQueueDao = env.db.editQueueDao(),
                            bodyService = env.bodyService,
                            database = env.db,
                            accountGeneration = env.accountGeneration,
                        )
                },
            ).build()
}
