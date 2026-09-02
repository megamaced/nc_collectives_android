package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.domain.model.SaveOutcome
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
 * Issue #40, and the seam issue #24 opened.
 *
 * `enqueueUpload` resolves a filename against the *local* attachment table
 * and hands it straight back; share capture writes that name into the page
 * body immediately. Issue #24 then made the upload guarded — `If-None-Match:
 * *`, so a name taken on the server comes back `412` and the row moves to the
 * next free name. Two naming decisions, seconds apart, with a body committed
 * between them.
 *
 * The whole failure lives in that gap, which is why it needed a real Room
 * table, a real WebDAV request and a real body save to reproduce: the page
 * ended up rendering *another client's* `photo.jpg` while the user's upload
 * sat at `photo-1.jpg` referenced by nothing.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentCollisionIntegrationTest {
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
    fun remoteCollisionRename_repointsTheBodyAtTheNameThatWillLand() =
        runTest {
            env.seedPage(
                id = 12,
                title = "Notes",
                bodyMd = "Shared from Chrome\n\n![photo.jpg](photo.jpg)",
                bodyEtag = "etag-1",
            )
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")
            // The refresh inside `renameForRemoteCollision` is best-effort; give
            // it an empty listing so the resolver falls back to stepping past our
            // own row, and let the body PUT succeed.
            dispatcher
                .on("/remote.php/dav", OcsResponses.webDav(207).setBody(EMPTY_MULTISTATUS), method = "PROPFIND")
                .on("/remote.php/dav", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")

            val renamed = env.attachmentRepository.renameForRemoteCollision(pageId = 12, fileName = "photo.jpg")
            assertEquals("photo-1.jpg", renamed)

            val followed = env.pageRepository.retargetAttachmentRef(
                pageId = 12,
                oldName = "photo.jpg",
                newName = renamed!!,
            )
            assertEquals(SaveOutcome.Saved, followed)

            val saved = env.db.pageDao().getById(12)
            assertEquals(
                "the body must point at the name the upload will actually use",
                "Shared from Chrome\n\n![photo.jpg](photo-1.jpg)",
                saved?.bodyMd,
            )
            assertEquals("etag-2", saved?.bodyEtag)

            val put = dispatcher.requestsWithMethod("PUT").single()
            assertEquals("\"etag-1\"", put.getHeader("If-Match"))
            assertTrue(put.body.readUtf8().contains("![photo.jpg](photo-1.jpg)"))
        }

    @Test
    fun remoteCollisionRename_movesTheRowAndItsBytesTogether() =
        runTest {
            env.seedPage(id = 12, bodyMd = "![photo.jpg](photo.jpg)")
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", bytes = "the only copy".toByteArray(), attempts = 3)
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(207).setBody(EMPTY_MULTISTATUS), method = "PROPFIND")

            env.attachmentRepository.renameForRemoteCollision(pageId = 12, fileName = "photo.jpg")

            assertNull(
                "the row at the refused name must be gone",
                env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo.jpg")),
            )
            val requeued = env.db.attachmentDao().getById(AttachmentEntity.key(12, "photo-1.jpg"))
            assertNotNull(requeued)
            assertEquals(AttachmentEntity.STATUS_PENDING, requeued?.status)
            assertEquals("a new filename is a new attempt chain (issue #30)", 0, requeued?.attempts)
            assertNull("the server id belonged to whatever sits at the old name", requeued?.serverAttachmentId)
            assertEquals("the only copy", env.stagedFile(12, "photo-1.jpg").readText())
            assertTrue(!env.stagedFile(12, "photo.jpg").exists())
        }

    @Test
    fun retargetingABodyThatNeverNamedTheAttachment_writesNothing() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Just prose, no attachments.", bodyEtag = "etag-1")

            val outcome = env.pageRepository.retargetAttachmentRef(12, "photo.jpg", "photo-1.jpg")

            assertEquals(SaveOutcome.Saved, outcome)
            assertTrue("no save should have gone out", dispatcher.requestsWithMethod("PUT").isEmpty())
            assertEquals(
                "Just prose, no attachments.",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.bodyMd,
            )
        }

    @Test
    fun retargetingWithAQueuedEdit_rewritesTheQueuedBodyNotTheStaleCachedOne() =
        runTest {
            // Offline edit waiting for the network. Its body is the local truth
            // about this page (issue #18), so that is what has to be repointed —
            // rewriting the row's server copy would drop the user's edit.
            env.seedPage(id = 12, bodyMd = "old server body\n\n![photo.jpg](photo.jpg)", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(
                com.megamaced.nccollectives.data.db.entity.EditQueueEntity(
                    pageId = 12,
                    baseEtag = "etag-1",
                    newBodyMd = "my offline edit\n\n![photo.jpg](photo.jpg)",
                    queuedAt = 1L,
                    status = "PENDING",
                ),
            )
            // Genuinely offline, not a 5xx: only a `NetworkError` queues, because
            // an HTTP error is a server that answered and said no — a different
            // outcome, deliberately not queued. Nothing listening is the closest
            // thing to a device with no network, and it fails immediately rather
            // than waiting out the 30-second read timeout.
            env.server.shutdown()

            val outcome = env.pageRepository.retargetAttachmentRef(12, "photo.jpg", "photo-1.jpg")

            assertEquals(SaveOutcome.Queued, outcome)
            assertEquals(
                "the queued edit keeps its own text and gains the new reference",
                "my offline edit\n\n![photo.jpg](photo-1.jpg)",
                env.db.editQueueDao().pendingBody(12),
            )
            assertEquals(
                "the server's cached copy is untouched until a save lands",
                "old server body\n\n![photo.jpg](photo.jpg)",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.bodyMd,
            )
            assertEquals(
                "the edit chain still declares what it was written against",
                "etag-1",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.baseEtag,
            )
        }

    private companion object {
        val EMPTY_MULTISTATUS =
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>"""
    }
}
