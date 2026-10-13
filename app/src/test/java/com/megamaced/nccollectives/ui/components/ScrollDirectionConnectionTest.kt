package com.megamaced.nccollectives.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollDirectionConnectionTest {
    private val reported = mutableListOf<ScrollDirection>()
    private var canScroll = true
    private val connection = ScrollDirectionConnection(thresholdPx = 10f, canScroll = { canScroll }) { reported += it }

    /** A finger moving up by [px] — content moving towards its end. */
    private fun forward(
        px: Float,
        source: NestedScrollSource = NestedScrollSource.UserInput,
    ) = connection.onPreScroll(Offset(0f, -px), source)

    private fun backward(px: Float) = connection.onPreScroll(Offset(0f, px), NestedScrollSource.UserInput)

    @Test
    fun consumesNothing() {
        assertEquals(Offset.Zero, forward(50f))
        assertEquals(Offset.Zero, backward(50f))
    }

    @Test
    fun reportsOnceTheThresholdIsCrossed() {
        forward(4f)
        forward(4f)
        assertEquals(emptyList<ScrollDirection>(), reported)
        forward(4f)
        assertEquals(listOf(ScrollDirection.Forward), reported)
    }

    @Test
    fun reportsBackwardForAPullAtTheTop() {
        backward(12f)
        assertEquals(listOf(ScrollDirection.Backward), reported)
    }

    @Test
    fun aReversalDiscardsWhatHadBuiltUp() {
        forward(8f)
        backward(3f)
        forward(8f)
        assertEquals(emptyList<ScrollDirection>(), reported)
        forward(3f)
        assertEquals(listOf(ScrollDirection.Forward), reported)
    }

    @Test
    fun aFlingReportsLikeADrag() {
        forward(30f, NestedScrollSource.SideEffect)
        assertEquals(listOf(ScrollDirection.Forward), reported)
    }

    @Test
    fun reportsNothingWhileTheContentCannotScroll() {
        canScroll = false
        forward(100f)
        backward(100f)
        assertEquals(emptyList<ScrollDirection>(), reported)
    }

    @Test
    fun keepsReportingAsTheScrollContinues() {
        forward(12f)
        forward(12f)
        backward(12f)
        assertEquals(listOf(ScrollDirection.Forward, ScrollDirection.Forward, ScrollDirection.Backward), reported)
    }
}
