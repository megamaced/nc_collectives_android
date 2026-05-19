package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.domain.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [DirectEditingRepositoryImpl.serverPathFor] — the only piece of
 * Batch 27 that does interesting logic rather than network I/O. The
 * `..` / control-char rejection inherits from
 * `ServerStringValidation.cleanPathSegment` (S-14′), so the cases below
 * are about *composition*, not validation rules.
 */
class DirectEditingRepositoryImplTest {
    private val repo = DirectEditingRepositoryImpl(service = ThrowingService)

    @Test
    fun serverPathFor_typicalPage_buildsSlashJoinedPath() {
        val path = repo.serverPathFor(pageOf(collectivePath = ".Collectives/Wiki", filePath = "Some Folder", fileName = "Page.md"))
        assertEquals(".Collectives/Wiki/Some Folder/Page.md", path)
    }

    @Test
    fun serverPathFor_emptyFilePath_stillBuildsValidPath() {
        // Landing pages live at the collective root with empty filePath.
        val path = repo.serverPathFor(pageOf(collectivePath = ".Collectives/Wiki", filePath = "", fileName = "Readme.md"))
        assertEquals(".Collectives/Wiki/Readme.md", path)
    }

    @Test
    fun serverPathFor_trimsLeadingAndTrailingSlashes() {
        val path = repo.serverPathFor(pageOf(collectivePath = "/.Collectives/Wiki/", filePath = "/Some Folder/", fileName = "Page.md"))
        assertEquals(".Collectives/Wiki/Some Folder/Page.md", path)
    }

    @Test
    fun serverPathFor_traversalInCollectivePath_returnsNull() {
        // S-14′: a compromised server feeding us `..` segments must not
        // be allowed to escape the user's Files root.
        val path = repo.serverPathFor(pageOf(collectivePath = "../..", filePath = "x", fileName = "Page.md"))
        assertNull(path)
    }

    @Test
    fun serverPathFor_traversalInFilePath_returnsNull() {
        val path = repo.serverPathFor(pageOf(collectivePath = ".Collectives/Wiki", filePath = "../escape", fileName = "Page.md"))
        assertNull(path)
    }

    @Test
    fun serverPathFor_traversalInFileName_returnsNull() {
        val path = repo.serverPathFor(pageOf(collectivePath = ".Collectives/Wiki", filePath = "Folder", fileName = ".."))
        assertNull(path)
    }

    @Test
    fun serverPathFor_controlCharInFileName_returnsNull() {
        val path = repo.serverPathFor(pageOf(collectivePath = ".Collectives/Wiki", filePath = "Folder", fileName = "Page\nName.md"))
        assertNull(path)
    }

    private fun pageOf(
        collectivePath: String,
        filePath: String,
        fileName: String,
    ): Page =
        Page(
            id = 1,
            collectiveId = 1,
            parentId = 0,
            title = "Test",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            isFullWidth = false,
            trashed = false,
            serverTimestamp = 0,
            size = 0,
            fileName = fileName,
            filePath = filePath,
            collectivePath = collectivePath,
            linkedPageIds = emptyList(),
            lastUserDisplayName = "",
            bodyMd = null,
            draftBodyMd = null,
        )

    private object ThrowingService : com.megamaced.nccollectives.data.api.DirectEditingService {
        override suspend fun getCapability() = error("Network not used in path-building tests")

        override suspend fun openSession(
            path: String,
            editorId: String?,
        ) = error("Network not used in path-building tests")
    }
}
