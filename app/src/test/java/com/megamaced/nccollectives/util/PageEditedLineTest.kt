package com.megamaced.nccollectives.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Pins the page-list subtitle, including the two half-populated cases —
 * both of which used to render as nothing at all.
 */
@RunWith(AndroidJUnit4::class)
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
            pageEditedLine("Ada", secondsAgo(10, TimeUnit.MINUTES), now)?.english(),
        )
        assertEquals(
            "Edited by Ada · 2 days ago",
            pageEditedLine("Ada", secondsAgo(2, TimeUnit.DAYS), now)?.english(),
        )
    }

    @Test
    fun missingTimestamp_stillNamesTheEditor() {
        assertEquals("Edited by Ada", pageEditedLine("Ada", 0L, now)?.english())
        assertEquals("Edited by Ada", pageEditedLine("Ada", -1L, now)?.english())
    }

    @Test
    fun missingName_stillDatesTheEdit() {
        assertEquals("Edited · 10 minutes ago", pageEditedLine("", secondsAgo(10, TimeUnit.MINUTES), now)?.english())
        // Whitespace is as absent as empty — the server sends both.
        assertEquals("Edited · 1 hour ago", pageEditedLine("   ", secondsAgo(1, TimeUnit.HOURS), now)?.english())
    }

    @Test
    fun nothingToSay_drawsNoLine() {
        assertNull(pageEditedLine("", 0L, now)?.english())
        assertNull(pageEditedLine("  ", -5L, now)?.english())
    }

    @Test
    fun timestampIsInterpretedAsSeconds() {
        // The regression that matters: page models carry seconds, the
        // relative-time helper takes millis. Treating one as the other
        // puts every page in 1970.
        val tenMinutesAgoInSeconds = secondsAgo(10, TimeUnit.MINUTES)
        assertEquals(
            "Edited by Ada · 10 minutes ago",
            pageEditedLine("Ada", tenMinutesAgoInSeconds, now)?.english(),
        )
    }
}
