package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Pins [DirectEditingRepositoryImpl.serverPathFor] — the only piece of
 * Batch 27 that does interesting logic rather than network I/O. The
 * `..` / control-char rejection inherits from
 * `ServerStringValidation.cleanPathSegment` (S-14′), so the cases below
 * are about *composition*, not validation rules.
 */
class DirectEditingRepositoryImplTest {
    @Test
    fun serverPathFor_typicalPage_buildsSlashJoinedPath() {
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = ".Collectives/Wiki", filePath = "Some Folder", fileName = "Page.md"),
        )
        assertEquals(".Collectives/Wiki/Some Folder/Page.md", path)
    }

    @Test
    fun serverPathFor_emptyFilePath_stillBuildsValidPath() {
        // Landing pages live at the collective root with empty filePath.
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = ".Collectives/Wiki", filePath = "", fileName = "Readme.md"),
        )
        assertEquals(".Collectives/Wiki/Readme.md", path)
    }

    @Test
    fun serverPathFor_trimsLeadingAndTrailingSlashes() {
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = "/.Collectives/Wiki/", filePath = "/Some Folder/", fileName = "Page.md"),
        )
        assertEquals(".Collectives/Wiki/Some Folder/Page.md", path)
    }

    @Test
    fun serverPathFor_traversalInCollectivePath_returnsNull() {
        // S-14′: a compromised server feeding us `..` segments must not
        // be allowed to escape the user's Files root.
        val path = DirectEditingRepositoryImpl.serverPathFor(pageOf(collectivePath = "../..", filePath = "x", fileName = "Page.md"))
        assertNull(path)
    }

    @Test
    fun serverPathFor_traversalInFilePath_returnsNull() {
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = ".Collectives/Wiki", filePath = "../escape", fileName = "Page.md"),
        )
        assertNull(path)
    }

    @Test
    fun serverPathFor_traversalInFileName_returnsNull() {
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = ".Collectives/Wiki", filePath = "Folder", fileName = ".."),
        )
        assertNull(path)
    }

    @Test
    fun serverPathFor_controlCharInFileName_returnsNull() {
        val path = DirectEditingRepositoryImpl.serverPathFor(
            pageOf(collectivePath = ".Collectives/Wiki", filePath = "Folder", fileName = "Page\nName.md"),
        )
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
}

/**
 * Issue #22: which capability probe outcomes are worth remembering.
 *
 * The bug was folding every non-success into a cached `false`, so one probe
 * made while offline — the likely first probe in an offline-first app —
 * disabled the collaborative editor until the process restarted.
 */
class CapabilityCacheabilityTest {
    @Test
    fun `a successful probe is remembered`() {
        assertEquals(
            CapabilityCache.Decided,
            capabilityCacheability(ApiResult.Success(Unit)),
        )
    }

    @Test
    fun `a 404 is a real negative and is remembered`() {
        // An older server, or one without the Text app, genuinely has no
        // directEditing endpoint, and that will not change until an admin
        // installs it.
        assertEquals(
            CapabilityCache.Decided,
            capabilityCacheability(ApiResult.HttpError(code = 404, message = "Not Found")),
        )
    }

    @Test
    fun `a network failure is not remembered`() {
        assertEquals(
            CapabilityCache.Undecided,
            capabilityCacheability(ApiResult.NetworkError(IOException("offline"))),
        )
    }

    @Test
    fun `a server error is not remembered`() {
        assertEquals(
            CapabilityCache.Undecided,
            capabilityCacheability(ApiResult.HttpError(code = 503, message = "Service Unavailable")),
        )
    }

    @Test
    fun `an unauthorised probe is not remembered`() {
        // The session is about to be re-established, not permanently gone.
        assertEquals(CapabilityCache.Undecided, capabilityCacheability(ApiResult.Unauthorised))
    }

    @Test
    fun `an unexpected failure is not remembered`() {
        assertEquals(
            CapabilityCache.Undecided,
            capabilityCacheability(ApiResult.Unexpected(IllegalStateException("boom"))),
        )
    }
}
