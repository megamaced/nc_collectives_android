package com.megamaced.nccollectives.integration

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Since Collectives 4.0 the attachments listing of a page with subpages also
 * names the other files in that page's folder (`type: folder`), beside the
 * page's `Readme.md` rather than in `.attachments.<pageId>/`. The app treated
 * every row as an attachment: it fetched them from the attachments directory,
 * where they are not, and offered a Delete the server answers with 404.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentPageFolderIntegrationTest {
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
    fun aPageFolderFile_isListedUnderItsOwnKey_besideAnAttachmentOfTheSameName() =
        runTest {
            seedFolderPage()
            dispatcher.on(
                LISTING,
                listing(attachment(501, "notes.pdf"), folderFile(502, "notes.pdf"), folderFile(503, "plan.png")),
                method = "GET",
            )

            assertTrue(env.attachmentRepository.refresh(PAGE) is ApiResult.Success)

            val rows = env.db
                .attachmentDao()
                .listForPage(PAGE)
                .associateBy { it.id }
            assertEquals(setOf("41/notes.pdf", "41/page-folder/notes.pdf", "41/page-folder/plan.png"), rows.keys)
            assertFalse(rows.getValue("41/notes.pdf").inPageFolder)
            assertTrue(rows.getValue("41/page-folder/notes.pdf").inPageFolder)
            assertEquals(502L, rows.getValue("41/page-folder/notes.pdf").serverAttachmentId)
        }

    @Test
    fun aPageFolderFilesPreview_pointsAtThePagesFolder_andAnAttachmentsAtItsDirectory() =
        runTest {
            seedFolderPage()
            dispatcher.on(LISTING, listing(attachment(501, "shot.png"), folderFile(503, "plan.png")), method = "GET")
            env.attachmentRepository.refresh(PAGE)

            val urls = env.attachmentRepository
                .observeForPage(PAGE)
                .first()
                .associate { it.fileName to it.remoteUrl }

            assertTrue("was ${urls["plan.png"]}", urls.getValue("plan.png")!!.endsWith("/.Collectives/Wiki/Folder/plan.png"))
            assertTrue(
                "was ${urls["shot.png"]}",
                urls.getValue("shot.png")!!.endsWith("/.Collectives/Wiki/Folder/.attachments.41/shot.png"),
            )
        }

    @Test
    fun aPageFolderFileOnTheLandingPage_isFetchedFromTheCollectivesRoot() =
        runTest {
            // The landing page's folder is the collective's own: filePath "".
            env.seedPage(id = PAGE, title = "Wiki", fileName = "Readme.md", filePath = "")
            dispatcher.on(LISTING, listing(folderFile(503, "plan.png")), method = "GET")
            env.attachmentRepository.refresh(PAGE)

            val url = env.attachmentRepository
                .observeForPage(PAGE)
                .first()
                .single()
                .remoteUrl

            assertTrue("was $url", url!!.endsWith("/.Collectives/Wiki/plan.png"))
        }

    @Test
    fun openingAPageFolderFile_downloadsItFromThePagesFolder() =
        runTest {
            seedFolderPage()
            dispatcher.on("/Folder/plan.png", MockResponse().setBody("plan bytes"), method = "GET")

            // What the attachments screen asks for a page-folder file.
            val result = env.attachmentRepository.downloadForViewing(PAGE, "plan.png")

            assertTrue("was $result", result is ApiResult.Success)
            val paths = dispatcher.requestsWithMethod("GET").map { it.path }
            assertEquals(listOf("/remote.php/dav/files/alice/.Collectives/Wiki/Folder/plan.png"), paths)
        }

    @Test
    fun anAttachmentAndAPageFolderFileOfTheSameName_areHandedOutAsSeparateFiles() =
        runTest {
            seedFolderPage()
            dispatcher
                .on("/Folder/.attachments.41/plan.pdf", MockResponse().setBody("attachment bytes"), method = "GET")
                .on("/Folder/plan.pdf", MockResponse().setBody("folder bytes"), method = "GET")

            val attachment = downloaded(".attachments.41/plan.pdf")
            val folderFile = downloaded("plan.pdf")

            // A viewer may still hold the first grant: it must keep serving
            // the attachment, not the page-folder file downloaded after it.
            assertNotEquals(attachment, folderFile)
            assertEquals("attachment bytes", read(attachment))
            assertEquals("folder bytes", read(folderFile))
        }

    @Test
    fun aFailedDownloadFromOneLocation_leavesTheOthersFileReadable() =
        runTest {
            seedFolderPage()
            dispatcher
                .on("/Folder/.attachments.41/plan.pdf", MockResponse().setBody("attachment bytes"), method = "GET")
                .on("/Folder/plan.pdf", MockResponse().setResponseCode(500), method = "GET")

            val attachment = downloaded(".attachments.41/plan.pdf")
            val failed = env.attachmentRepository.downloadForViewing(PAGE, "plan.pdf")

            assertTrue("was $failed", failed is ApiResult.HttpError)
            assertEquals("attachment bytes", read(attachment))
        }

    @Test
    fun aPageFolderFileThatHasGone_isReconciledAway_andTheAttachmentKept() =
        runTest {
            seedFolderPage()
            dispatcher.on(
                LISTING,
                listing(attachment(501, "notes.pdf"), folderFile(503, "plan.png")),
                listing(attachment(501, "notes.pdf")),
                method = "GET",
            )

            env.attachmentRepository.refresh(PAGE)
            env.attachmentRepository.refresh(PAGE)

            assertEquals(
                listOf("41/notes.pdf"),
                env.db
                    .attachmentDao()
                    .listForPage(PAGE)
                    .map { it.id },
            )
        }

    @Test
    fun aPageThatIsReissuedAnId_keepsItsPageFolderFilesInTheirOwnNamespace() =
        runTest {
            seedFolderPage()
            dispatcher.on(LISTING, listing(attachment(501, "notes.pdf"), folderFile(502, "notes.pdf")), method = "GET")
            env.attachmentRepository.refresh(PAGE)

            env.attachmentRepository.rekeyForPage(PAGE, 99)

            val keys = env.db
                .attachmentDao()
                .listForPage(99)
                .map { it.id }
                .toSet()
            assertEquals(setOf(AttachmentEntity.key(99, "notes.pdf"), AttachmentEntity.folderFileKey(99, "notes.pdf")), keys)
        }

    @Test
    fun aListingFromBeforeCollectives4_isReadAsAttachments() =
        runTest {
            seedFolderPage()
            // No `type` at all, as an older server sends it.
            dispatcher.on(
                LISTING,
                OcsResponses.envelope("""{"attachments":[{"id":3,"name":"a.png","filesize":1,"mimetype":"image/png"}]}"""),
                method = "GET",
            )

            env.attachmentRepository.refresh(PAGE)

            val row = env.db
                .attachmentDao()
                .listForPage(PAGE)
                .single()
            assertEquals("41/a.png", row.id)
            assertFalse(row.inPageFolder)
        }

    private suspend fun downloaded(relativePath: String): Uri {
        val result = env.attachmentRepository.downloadForViewing(PAGE, relativePath)
        assertTrue("downloading $relativePath was $result", result is ApiResult.Success)
        return (result as ApiResult.Success).data.uri
    }

    private fun read(uri: Uri): String =
        env.context.contentResolver
            .openInputStream(uri)!!
            .use { it.readBytes().decodeToString() }

    /** A page with subpages: `Folder/Readme.md`, so its folder is `Folder`. */
    private suspend fun seedFolderPage() {
        env.seedPage(id = PAGE, title = "Folder", fileName = "Readme.md", filePath = "Folder")
    }

    private fun attachment(
        id: Long,
        name: String,
    ) = entry(id, name, "text", "Folder/.attachments.41/$name")

    private fun folderFile(
        id: Long,
        name: String,
    ) = entry(id, name, "folder", "Folder/$name")

    /** One row as Collectives 4.8's `AttachmentService::fileToInfo` sends it. */
    private fun entry(
        id: Long,
        name: String,
        type: String,
        relative: String,
    ): String =
        """{"id":$id,"name":"$name","filesize":10,"mimetype":"${if (name.endsWith(".png")) "image/png" else "application/pdf"}",""" +
            """"timestamp":1700000000,"path":"/.Collectives/Wiki/$relative","src":"$name",""" +
            """"internalPath":"files/.Collectives/Wiki/$relative","hasPreview":false,"type":"$type"}"""

    private fun listing(vararg entries: String) = OcsResponses.envelope("""{"attachments":[${entries.joinToString(",")}]}""")

    private companion object {
        const val PAGE = 41L
        const val LISTING = "/pages/41/attachments"
    }
}
