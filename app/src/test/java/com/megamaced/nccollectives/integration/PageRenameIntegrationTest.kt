package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
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
 * Issue #39: a rename or move can make Nextcloud reissue the page's file id
 * (`CollectivesApiService.updatePage`, spec gotcha #16), and every scrap of
 * local state is keyed on the old one.
 *
 * These are the tests the pure-function suite could not express: the bug was
 * not in a decision, it was in the *order* of a network reply, a Room
 * reconciliation keyed by id, and a hand-written cascade — three things that
 * only meet each other at runtime.
 */
@RunWith(AndroidJUnit4::class)
class PageRenameIntegrationTest {
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
    fun renameKeepingTheId_leavesLocalStateExactlyWhereItWas() =
        runTest {
            env.seedPage(id = 41, title = "Old", bodyMd = "# body", bodyEtag = "etag-1", draftBodyMd = "# my draft")
            env.db.editQueueDao().upsert(queuedEdit(pageId = 41, body = "# queued"))

            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 41, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 41, title = "New")), method = "GET")

            val result = env.pageRepository.renamePage(pageId = 41, newTitle = "New")

            assertTrue("rename should succeed, was $result", result is ApiResult.Success)
            val row = env.db.pageDao().getById(41)
            assertEquals("New", row?.title)
            assertEquals("# body", row?.bodyMd)
            assertEquals("# my draft", row?.draftBodyMd)
            assertEquals("# queued", env.db.editQueueDao().pendingBody(41))
        }

    @Test
    fun renameReissuingTheId_carriesTheQueuedEditAndDraftToTheNewId() =
        runTest {
            env.seedPage(id = 41, title = "Old", bodyMd = "# body", bodyEtag = "etag-1", draftBodyMd = "# my draft")
            env.db.editQueueDao().upsert(queuedEdit(pageId = 41, body = "# queued offline edit"))

            // The server accepts the rename and hands back a *different* id, then
            // lists the collective under that id alone — page 41 is simply gone.
            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "New")), method = "GET")

            val result = env.pageRepository.renamePage(pageId = 41, newTitle = "New")
            assertTrue("rename should succeed, was $result", result is ApiResult.Success)

            assertNull("the old id must not survive", env.db.pageDao().getById(41))
            val moved = env.db.pageDao().getById(99)
            assertNotNull("the page should exist under its new id", moved)
            assertEquals("New", moved?.title)
            assertEquals("the cached body should travel with the page", "# body", moved?.bodyMd)
            assertEquals("etag-1", moved?.bodyEtag)
            assertEquals("the conflict draft should travel with the page", "# my draft", moved?.draftBodyMd)

            assertNull("the queue row must not be left under the dead id", env.db.editQueueDao().pendingBody(41))
            assertEquals(
                "the queued offline edit should travel with the page",
                "# queued offline edit",
                env.db.editQueueDao().pendingBody(99),
            )
        }

    @Test
    fun renameReissuingTheId_carriesStagedUploadBytesToTheNewId() =
        runTest {
            env.seedPage(id = 41, title = "Old", bodyMd = "# body")
            env.seedStagedUpload(pageId = 41, fileName = "photo.jpg", bytes = "the only copy".toByteArray())

            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "New")), method = "GET")

            env.pageRepository.renamePage(pageId = 41, newTitle = "New")

            assertTrue(
                "the old row must be gone",
                env.db
                    .attachmentDao()
                    .listForPage(41)
                    .isEmpty(),
            )
            val carried = env.db.attachmentDao().getById(AttachmentEntity.key(99, "photo.jpg"))
            assertNotNull("the pending upload should exist under the new page id", carried)
            assertEquals(AttachmentEntity.STATUS_PENDING, carried?.status)

            val staged = env.stagedFile(pageId = 99, fileName = "photo.jpg")
            assertTrue("the staged bytes should have moved with the row", staged.exists())
            assertEquals("the only copy", staged.readText())
            assertEquals(
                "the row must point at the file that exists",
                android.net.Uri
                    .fromFile(staged)
                    .toString(),
                carried?.localUriString,
            )
            assertTrue(
                "nothing should be left at the old staging path",
                !env.stagedFile(pageId = 41, fileName = "photo.jpg").exists(),
            )
        }

    @Test
    fun moveReissuingTheId_carriesLocalStateToo() =
        runTest {
            env.seedPage(id = 41, title = "Child", parentId = 1, bodyMd = "# body", draftBodyMd = "# draft")
            env.seedPage(id = 2, title = "New Parent")
            env.db.editQueueDao().upsert(queuedEdit(pageId = 41, body = "# queued"))

            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "Child", parentId = 2), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(
                        OcsResponses.page(id = 2, title = "New Parent"),
                        OcsResponses.page(id = 99, title = "Child", parentId = 2),
                    ),
                    method = "GET",
                )

            val result = env.pageRepository.movePage(pageId = 41, newParentPageId = 2)
            assertTrue("move should succeed, was $result", result is ApiResult.Success)

            assertNull(env.db.pageDao().getById(41))
            assertEquals(
                "# body",
                env.db
                    .pageDao()
                    .getById(99)
                    ?.bodyMd,
            )
            assertEquals(
                "# draft",
                env.db
                    .pageDao()
                    .getById(99)
                    ?.draftBodyMd,
            )
            assertEquals("# queued", env.db.editQueueDao().pendingBody(99))
        }

    @Test
    fun renameOntoAnIdAStaleRowAlreadyOccupies_migratesRatherThanFailing() =
        runTest {
            // `edit_queue.pageId` is the primary key (B-41), so repointing
            // onto an occupied id is a constraint failure that would abort
            // the whole migration — and leave the state loss this fix exists
            // to prevent. Contrived, but the cost of surviving it is one
            // DELETE.
            env.seedPage(id = 41, title = "Old", bodyMd = "# body")
            env.seedPage(id = 99, title = "Stale cache entry", bodyMd = "# stale")
            env.db.editQueueDao().upsert(queuedEdit(pageId = 41, body = "# the edit that matters"))
            env.db.editQueueDao().upsert(queuedEdit(pageId = 99, body = "# stale queued edit"))

            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "New")), method = "GET")

            val result = env.pageRepository.renamePage(pageId = 41, newTitle = "New")

            assertTrue("rename should succeed, was $result", result is ApiResult.Success)
            assertEquals(
                "the migrated edit wins; the stale one was for a page the server no longer has there",
                "# the edit that matters",
                env.db.editQueueDao().pendingBody(99),
            )
            assertEquals(
                "# body",
                env.db
                    .pageDao()
                    .getById(99)
                    ?.bodyMd,
            )
        }

    private fun queuedEdit(
        pageId: Long,
        body: String,
    ) = com.megamaced.nccollectives.data.db.entity.EditQueueEntity(
        pageId = pageId,
        baseEtag = "etag-1",
        newBodyMd = body,
        queuedAt = 1L,
        status = "PENDING",
    )
}
