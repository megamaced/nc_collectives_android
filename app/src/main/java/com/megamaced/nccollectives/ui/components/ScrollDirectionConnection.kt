package com.megamaced.nccollectives.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.sign

/** Which way the user is moving through the content. */
enum class ScrollDirection {
    /** Towards the end of the content: the finger moves up. */
    Forward,

    /** Towards the start of the content: the finger moves down, including a pull at the top. */
    Backward,
}

/**
 * Reports the direction of a scroll once it has travelled [thresholdPx] one
 * way, and consumes nothing, so the scrollable, the top app bar and
 * pull-to-refresh below it see every pixel as if this were not there.
 *
 * Reads pre-scroll deltas, so it also reports a pull at the very top of the
 * content (where nothing scrolls) as [ScrollDirection.Backward]. A fling
 * reports in the same way as a drag. Nothing is reported while [canScroll]
 * is false: on content that fits the screen there is nothing to make room
 * for, and hiding chrome there would only take controls away.
 *
 * The threshold keeps a finger's jitter from flapping whatever listens to
 * this. Movement the other way discards what had built up.
 */
class ScrollDirectionConnection(
    private val thresholdPx: Float,
    private val canScroll: () -> Boolean,
    private val onDirection: (ScrollDirection) -> Unit,
) : NestedScrollConnection {
    private var accumulated = 0f

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val dy = available.y
        if (dy == 0f || !canScroll()) return Offset.Zero
        if (accumulated != 0f && sign(dy) != sign(accumulated)) accumulated = 0f
        accumulated += dy
        when {
            accumulated <= -thresholdPx -> {
                accumulated = 0f
                onDirection(ScrollDirection.Forward)
            }

            accumulated >= thresholdPx -> {
                accumulated = 0f
                onDirection(ScrollDirection.Backward)
            }
        }
        return Offset.Zero
    }
}
