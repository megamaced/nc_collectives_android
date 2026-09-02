package com.megamaced.nccollectives.ui.screen.share

import com.megamaced.nccollectives.domain.model.SaveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issues #25 and #31 — what a share capture is allowed to claim.
 *
 * #25: `handleCreate` discarded its final `appendToPage` result, so a page
 * whose image references were never saved reported a clean capture. #31: two
 * more ways to fall short, both *before* that append and so invisible to
 * #25's check — the shared text failing its own write, and shared images
 * never being staged at all.
 */
class CaptureReportTest {
    private fun created(
        imageRefs: String = "![a](a.png)",
        bodyOutcome: SaveOutcome = SaveOutcome.Saved,
        imagesDropped: Int = 0,
        hasText: Boolean = true,
    ) = PartialCreate(
        pageId = 7L,
        pageTitle = "Notes",
        imageRefs = imageRefs,
        bodyOutcome = bodyOutcome,
        imagesDropped = imagesDropped,
        hasText = hasText,
    )

    @Test
    fun `everything landing says so plainly`() {
        val report = captureReport(created(), SaveOutcome.Saved)
        assertEquals("""Saved as "Notes"""", report.message)
        assertTrue(report.succeeded)
        assertFalse(report.resumable)
    }

    @Test
    fun `queued image links are a success that says it will sync`() {
        // The offline design working, not a failure — but not the same thing
        // as being on the server either.
        val report = captureReport(created(), SaveOutcome.Queued)
        assertTrue(report.succeeded)
        assertTrue(report.message.contains("sync"))
        assertNotEquals(captureReport(created(), SaveOutcome.Saved).message, report.message)
    }

    @Test
    fun `a failed reference save is the one case worth retrying`() {
        // The page exists and trying again would finish it, so the payload is
        // kept and PartialCreate resumes rather than creating a second page.
        val report = captureReport(created(), SaveOutcome.Error("no webdav url"))
        assertFalse(report.succeeded)
        assertTrue(report.resumable)
        assertTrue(report.message.contains("no webdav url"))
    }

    @Test
    fun `a queued text write is reported alongside the page`() {
        // Issue #31: createPage's body outcome used to be logged and dropped.
        val report = captureReport(created(bodyOutcome = SaveOutcome.Queued), SaveOutcome.Saved)
        assertTrue(report.succeeded)
        assertTrue(report.message.contains("text"))
    }

    @Test
    fun `a failed text write is named, and is not resumable`() {
        // A 403 or a 5xx on the initial body. The page is there and empty;
        // retrying the append cannot fix the text, so don't offer it.
        val report = captureReport(created(bodyOutcome = SaveOutcome.Error("Server error 500")), SaveOutcome.Saved)
        assertTrue(report.message.contains("Server error 500"))
        assertFalse(report.resumable)
    }

    @Test
    fun `dropped images are reported even when everything else worked`() {
        val report = captureReport(created(imagesDropped = 2), SaveOutcome.Saved)
        assertTrue(report.message.contains("2 images couldn't be read"))
        // Still a success: the text and the readable images are saved, and a
        // URI that couldn't be staged will not stage on a second attempt.
        assertTrue(report.succeeded)
        assertFalse(report.resumable)
    }

    @Test
    fun `one dropped image is singular`() {
        assertTrue(captureReport(created(imagesDropped = 1), SaveOutcome.Saved).message.contains("1 image couldn't"))
    }

    @Test
    fun `an image-only share that staged nothing is not a save`() {
        // The blank-page case from issue #31: every URI failed, there was no
        // text, and this used to report "Saved".
        val report = captureReport(
            created(imageRefs = "", imagesDropped = 3, hasText = false),
            SaveOutcome.Saved,
        )
        assertFalse(report.succeeded)
        assertFalse(report.resumable)
        assertTrue(report.message.contains("3 images couldn't be read"))
    }

    @Test
    fun `several losses are all listed`() {
        val report = captureReport(
            created(bodyOutcome = SaveOutcome.Queued, imagesDropped = 1),
            SaveOutcome.Queued,
        )
        assertTrue(report.message.contains("text"))
        assertTrue(report.message.contains("image links"))
        assertTrue(report.message.contains("1 image couldn't be read"))
    }

    @Test
    fun `every arm names the page`() {
        val outcomes = listOf(SaveOutcome.Saved, SaveOutcome.Queued, SaveOutcome.Conflict, SaveOutcome.Error("x"))
        outcomes.forEach { assertTrue(captureReport(created(), it).message.contains("Notes")) }
    }
}

/** The append half. Issue #31: a dropped image was invisible here too. */
class AppendReportTest {
    @Test
    fun `a clean append says so`() {
        val report = appendReport(SaveOutcome.Saved, imagesDropped = 0)
        assertEquals("Appended", report.message)
        assertTrue(report.succeeded)
    }

    @Test
    fun `a queued append says it will sync`() {
        assertTrue(appendReport(SaveOutcome.Queued, imagesDropped = 0).message.contains("sync"))
    }

    @Test
    fun `dropped images are appended to the message`() {
        val report = appendReport(SaveOutcome.Saved, imagesDropped = 2)
        assertTrue(report.message.startsWith("Appended"))
        assertTrue(report.message.contains("2 images couldn't be read"))
        assertTrue(report.succeeded)
    }

    @Test
    fun `a failed append reports the failure and nothing else`() {
        // Adding "and also 2 images failed" to a message the user is already
        // being told is an error only buries the actionable part.
        val report = appendReport(SaveOutcome.Error("Page not cached"), imagesDropped = 2)
        assertEquals("Page not cached", report.message)
        assertFalse(report.succeeded)
    }

    @Test
    fun `an append is never resumable`() {
        // Unlike a create, nothing has been half-built that a retry has to
        // avoid duplicating.
        assertFalse(appendReport(SaveOutcome.Error("x"), imagesDropped = 0).resumable)
    }
}

/** Nothing the share carried could be saved (issue #31). */
class NothingToSaveMessageTest {
    @Test
    fun `dropped images are named as the reason`() {
        assertTrue(nothingToSaveMessage(imagesDropped = 2).contains("2 images couldn't be read"))
    }

    @Test
    fun `an empty share says that instead`() {
        assertTrue(nothingToSaveMessage(imagesDropped = 0).contains("empty"))
    }
}
