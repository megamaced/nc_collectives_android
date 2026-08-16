package com.megamaced.nccollectives.ui.screen.page

import com.megamaced.nccollectives.data.prefs.TextScale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [textZoomPercent] — the bridge between the app's
 * `TextScale` preference and the collaborative editor's WebView.
 *
 * The point of pinning this is that the two editors have to agree: the
 * native side multiplies `bodyLarge` by the same ratio this converts to a
 * zoom percentage. If the mapping drifts, switching editors changes the
 * text size under the user, which is the bug this feature exists to fix.
 */
class TextZoomPercentTest {
    @Test
    fun defaultScale_isUnzoomed() {
        // 100 is the WebView default — at `TextScale.Default` we must not
        // be applying any zoom of our own on top of the OS font setting.
        assertEquals(100, textZoomPercent(TextScale.Default.multiplier))
    }

    @Test
    fun everyPreferenceStep_mapsInsideTheClamp() {
        val zooms = TextScale.entries.map { textZoomPercent(it.multiplier) }
        assertEquals(listOf(85, 100, 120, 140), zooms)
    }

    @Test
    fun stepsAreStrictlyIncreasing() {
        // Guards a future edit that adds a step out of order — the radio
        // list renders in declaration order, so "Large" below "Default"
        // would read as a broken setting.
        val zooms = TextScale.entries.map { textZoomPercent(it.multiplier) }
        assertEquals(zooms.sorted(), zooms)
        assertEquals(zooms.distinct().size, zooms.size)
    }

    @Test
    fun fractionalScale_rounds() {
        assertEquals(113, textZoomPercent(1.125f))
        assertEquals(112, textZoomPercent(1.124f))
    }

    @Test
    fun outOfRangeScale_isClamped() {
        // A corrupted preference or a careless new step shouldn't render
        // the editor unusable.
        assertEquals(200, textZoomPercent(50f))
        assertEquals(50, textZoomPercent(0f))
        assertEquals(50, textZoomPercent(-1f))
    }
}
