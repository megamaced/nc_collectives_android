package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the page-list subtitle, including the two half-populated cases —
 * both of which used to render as nothing at all.
 */
class PageEditedLineTest {
    private val now = TimeUnit.DAYS.toMillis(20_000)

    private fun secondsAgo(
        amount: Long,
        unit: TimeUnit,
    ): Long = TimeUnit.MILLISECONDS.toSeconds(now - unit.toMillis(amount))

    @Test
    fun nameAndTimestamp_readTogether() {
        assertEquals(
            "Edited by Ada · 10 minutes ago",
            pageEditedLine("Ada", secondsAgo(10, TimeUnit.MINUTES), now),
        )
        assertEquals(
            "Edited by Ada · 2 days ago",
            pageEditedLine("Ada", secondsAgo(2, TimeUnit.DAYS), now),
        )
    }

    @Test
    fun missingTimestamp_stillNamesTheEditor() {
        assertEquals("Edited by Ada", pageEditedLine("Ada", 0L, now))
        assertEquals("Edited by Ada", pageEditedLine("Ada", -1L, now))
    }

    @Test
    fun missingName_stillDatesTheEdit() {
        assertEquals("Edited 10 minutes ago", pageEditedLine("", secondsAgo(10, TimeUnit.MINUTES), now))
        // Whitespace is as absent as empty — the server sends both.
        assertEquals("Edited 1 hour ago", pageEditedLine("   ", secondsAgo(1, TimeUnit.HOURS), now))
    }

    @Test
    fun nothingToSay_drawsNoLine() {
        assertNull(pageEditedLine("", 0L, now))
        assertNull(pageEditedLine("  ", -5L, now))
    }

    @Test
    fun timestampIsInterpretedAsSeconds() {
        // The regression that matters: page models carry seconds, the
        // relative-time helper takes millis. Treating one as the other
        // puts every page in 1970.
        val tenMinutesAgoInSeconds = secondsAgo(10, TimeUnit.MINUTES)
        assertEquals(
            "Edited by Ada · 10 minutes ago",
            pageEditedLine("Ada", tenMinutesAgoInSeconds, now),
        )
    }
}
