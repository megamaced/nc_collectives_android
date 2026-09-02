package com.megamaced.nccollectives.ui.screen.share

import com.megamaced.nccollectives.domain.model.SaveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #25: `handleCreate` discarded its final `appendToPage` result, so a
 * page whose image references were never saved reported a clean capture —
 * images staged and uploading, and nothing in the body pointing at them.
 */
class SavedMessageTest {
    @Test
    fun `a fully saved capture says so plainly`() {
        assertEquals("""Saved as "Notes"""", savedMessage("Notes", SaveOutcome.Saved))
    }

    @Test
    fun `a queued capture is still a success, and says it will sync`() {
        // The offline design working, not a failure — but not the same
        // thing as being on the server either.
        val message = savedMessage("Notes", SaveOutcome.Queued)
        assertTrue(message.startsWith("""Saved as "Notes""""))
        assertTrue(message.contains("sync"))
        assertNotEquals(savedMessage("Notes", SaveOutcome.Saved), message)
    }

    @Test
    fun `a conflicted capture points at the draft`() {
        assertTrue(savedMessage("Notes", SaveOutcome.Conflict).contains("draft"))
    }

    @Test
    fun `a failed reference save names the failure`() {
        val message = savedMessage("Notes", SaveOutcome.Error("no webdav url"))
        assertTrue(message.contains("no webdav url"))
    }

    @Test
    fun `every outcome names the page`() {
        val outcomes = listOf(
            SaveOutcome.Saved,
            SaveOutcome.Queued,
            SaveOutcome.Conflict,
            SaveOutcome.Error("boom"),
        )
        outcomes.forEach { assertTrue(savedMessage("Notes", it).contains("Notes")) }
    }
}
