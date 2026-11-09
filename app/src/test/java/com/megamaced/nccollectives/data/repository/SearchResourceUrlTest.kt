package com.megamaced.nccollectives.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.repository.SearchRepositoryImpl.Companion.pageIdFromResourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins [SearchRepositoryImpl.pageIdFromResourceUrl] against the link shapes
 * Collectives' `PageService::getPageLink` produces. Robolectric because the
 * parse goes through `android.net.Uri` (B-47).
 */
@RunWith(AndroidJUnit4::class)
class SearchResourceUrlTest {
    private val base = "https://cloud.example.com/index.php/apps/collectives"

    @Test
    fun anUnsluggedPage_isReadFromItsFileIdQuery() {
        assertEquals(42L, pageIdFromResourceUrl("$base/Team/Folder/Meeting%20notes?fileId=42"))
    }

    @Test
    fun aFileIdFollowedByAFragment_isStillRead() {
        // B-47's regression: `?fileId=42#anchor` once parsed as "42#anchor".
        assertEquals(42L, pageIdFromResourceUrl("$base/Team/Page?fileId=42#anchor"))
    }

    @Test
    fun aSluggedPage_isReadFromItsSlugSegment() {
        // Issue #85: no fileId at all, only `<slug>-<id>`.
        assertEquals(1234L, pageIdFromResourceUrl("$base/Team/meeting-notes-1234"))
    }

    @Test
    fun aSluggedPageInASluggedCollective_takesThePagesIdNotTheCollectives() {
        assertEquals(1234L, pageIdFromResourceUrl("$base/team-7/meeting-notes-1234"))
    }

    @Test
    fun aSluggedPage_isReadWithPrettyUrlsAndASubdirectoryInstall() {
        assertEquals(1234L, pageIdFromResourceUrl("https://cloud.example.com/apps/collectives/Team/notes-1234"))
        assertEquals(1234L, pageIdFromResourceUrl("https://example.com/nextcloud/index.php/apps/collectives/Team/notes-1234"))
    }

    @Test
    fun aSlugWhoseTitleEndsInDigits_takesOnlyTheTrailingId() {
        assertEquals(88L, pageIdFromResourceUrl("$base/Team/report-2024-88"))
    }

    @Test
    fun aTrailingSlash_doesNotHideTheSlugSegment() {
        assertEquals(1234L, pageIdFromResourceUrl("$base/Team/meeting-notes-1234/"))
    }

    @Test
    fun aLandingPageLink_namesNoPage_evenWhenTheCollectiveSegmentEndsInAnId() {
        // The collective segment alone must never be read as a page id.
        assertNull(pageIdFromResourceUrl("$base/team-7"))
        assertNull(pageIdFromResourceUrl("$base/Team"))
    }

    @Test
    fun aDeeperPathWithoutAFileId_isNotGuessedAt() {
        // A slugged link never carries a folder path; anything deeper is an
        // unslugged page whose fileId went missing, and its title is not an id.
        assertNull(pageIdFromResourceUrl("$base/Team/Folder/Page-2024"))
    }

    @Test
    fun aSegmentWithoutATrailingId_namesNoPage() {
        assertNull(pageIdFromResourceUrl("$base/Team/meeting-notes"))
    }

    @Test
    fun aLinkOutsideTheCollectivesApp_namesNoPage() {
        assertNull(pageIdFromResourceUrl("https://cloud.example.com/index.php/apps/files/Team/notes-1234"))
    }

    @Test
    fun anOpaqueUri_namesNoPage_ratherThanThrowing() {
        assertNull(pageIdFromResourceUrl("mailto:someone@example.com"))
    }
}
