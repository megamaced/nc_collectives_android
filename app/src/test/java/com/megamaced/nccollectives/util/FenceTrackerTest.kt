package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins B-70. The dialect transforms walk a page line by line and skip fenced
 * code; the flag driving that used to be a single boolean toggled by any line
 * starting with ` ``` ` **or** `~~~`. A `~~~` line inside a ` ``` ` block — a
 * markdown sample that happens to mention the other fence style — flipped it
 * early, and from that point on the tracker was inverted for the rest of the
 * document: prose treated as code, code rewritten as prose.
 *
 * Both the tracker and the two transforms that use it are covered, because
 * the tracker being right is only interesting if the transforms actually
 * agree with it.
 */
class FenceTrackerTest {
    @Test
    fun tracker_closesOnlyOnAMatchingFenceCharacter() {
        val fence = FenceTracker()
        assertTrue("opening ``` is a delimiter", fence.accept("```kotlin"))
        assertTrue(fence.inFence)
        assertFalse("~~~ inside a ``` block is content", fence.accept("~~~"))
        assertTrue("…so we're still inside the block", fence.inFence)
        assertTrue("the matching ``` closes it", fence.accept("```"))
        assertFalse(fence.inFence)
    }

    @Test
    fun tracker_tildeFencesWorkTheSameWayRound() {
        val fence = FenceTracker()
        assertTrue(fence.accept("~~~"))
        assertFalse(fence.accept("``` not a delimiter here"))
        assertTrue(fence.inFence)
        assertTrue(fence.accept("~~~"))
        assertFalse(fence.inFence)
    }

    @Test
    fun tracker_indentedFencesStillCount() {
        val fence = FenceTracker()
        assertTrue(fence.accept("  ```"))
        assertTrue(fence.inFence)
    }

    @Test
    fun rewriteCallouts_calloutAfterAMixedFenceBlock_isStillRewritten() {
        val input =
            """
            ```
            ~~~
            ```
            > [!INFO]
            > after the block
            """.trimIndent()
        val out = rewriteCallouts(input)
        assertTrue("the callout after the block must be rewritten", out.contains("> ℹ️ **Info**"))
        assertTrue("and the block's contents must survive", out.contains("~~~"))
    }

    @Test
    fun rewriteCallouts_calloutInsideAMixedFenceBlock_isNotRewritten() {
        val input =
            """
            ```
            ~~~
            > [!INFO]
            ```
            """.trimIndent()
        assertEquals(input, rewriteCallouts(input))
    }

    @Test
    fun rewriteFootnotes_definitionAfterAMixedFenceBlock_isStillExtracted() {
        val input =
            """
            ```
            ~~~
            ```
            As noted[^1] earlier.

            [^1]: The note.
            """.trimIndent()
        val out = rewriteFootnotes(input)
        assertTrue("the reference should become a superscript", out.contains("<sup>1</sup>"))
        assertTrue(out.contains("**Footnotes**"))
        assertTrue(out.contains("1. The note."))
    }

    @Test
    fun rewriteFootnotes_definitionInsideAMixedFenceBlock_isLeftAlone() {
        val input =
            """
            ```
            ~~~
            [^1]: not a real definition
            ```
            """.trimIndent()
        assertEquals(input, rewriteFootnotes(input))
    }
}
