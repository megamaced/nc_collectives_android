package com.megamaced.nccollectives.ui.navigation

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The navigation bar's labels: each whole and on one line, on a phone at
 * the default font size and enlarged, and on narrower windows. A label that
 * would otherwise wrap shrinks to fit, but not past [SMALLEST_TAB_LABEL] of
 * its default size.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so a label's width is real.
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class TabLabelTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var density: Density
    private lateinit var measurer: TextMeasurer

    private fun showBar() {
        rule.setContent {
            density = LocalDensity.current
            measurer = rememberTextMeasurer()
            NcCollectivesTheme(dynamicColor = false) {
                val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
                NavigationSuiteScaffold(
                    navigationItems = {
                        TopLevelTabItems(
                            currentTab = TopLevelTab.Home,
                            unseenAlerts = 2,
                            navigationSuiteType = type,
                            onTabClick = { _, _ -> },
                        )
                    },
                    navigationSuiteType = type,
                ) {}
            }
        }
        rule.waitForIdle()
    }

    private fun label(tab: TopLevelTab): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(tab.label, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    /** [label]'s text at its full size, at [fontScale], within its slot's width or [unbounded]. */
    private fun atFullSize(
        label: TextLayoutResult,
        fontScale: Float,
        unbounded: Boolean = false,
    ): TextLayoutResult =
        measurer.measure(
            text = label.layoutInput.text,
            style = label.layoutInput.style,
            constraints = if (unbounded) Constraints() else Constraints(maxWidth = label.layoutInput.constraints.maxWidth),
            density = Density(density.density, fontScale),
        )

    /** How wide [label] is drawn, as a share of its width at the default font size. */
    private fun shareOfDefaultSize(label: TextLayoutResult): Float =
        label.size.width / atFullSize(label, fontScale = 1f, unbounded = true).size.width.toFloat()

    private fun assertWholeOnOneLine(tab: TopLevelTab) {
        val layout = label(tab)
        assertEquals("${tab.label}: lines", 1, layout.lineCount)
        assertFalse("${tab.label}: cut short", layout.isLineEllipsized(0))
    }

    /** On a phone, every label fits at its default size or larger. */
    private fun assertEveryLabelWholeAndAtLeastItsDefaultSize() {
        TopLevelTab.entries.forEach { tab ->
            assertWholeOnOneLine(tab)
            val share = shareOfDefaultSize(label(tab))
            assertTrue("${tab.label} no smaller than at the default font size: $share", share >= 0.995f)
        }
    }

    @Test
    fun atTheDefaultFontSizeEveryLabelIsWholeAndFullSize() {
        showBar()

        assertEquals(1f, density.fontScale)
        assertEveryLabelWholeAndAtLeastItsDefaultSize()
    }

    @Test
    @Config(fontScale = 1.3f)
    fun anEnlargedFontShrinksALongLabelToFitRatherThanWrapping() {
        showBar()

        assertEquals(1.3f, density.fontScale)
        assertEquals("at full size it would wrap", 2, atFullSize(label(TopLevelTab.Collectives), fontScale = 1.3f).lineCount)
        assertEveryLabelWholeAndAtLeastItsDefaultSize()
    }

    @Test
    @Config(fontScale = 2f)
    fun atTheLargestFontEveryLabelIsStillWhole() {
        showBar()

        assertEquals(2f, density.fontScale)
        assertEveryLabelWholeAndAtLeastItsDefaultSize()
    }

    @Test
    @Config(qualifiers = "w300dp-h700dp-xxhdpi")
    fun aNarrowerWindowShrinksALongLabelALittle() {
        showBar()

        val collectives = label(TopLevelTab.Collectives)
        assertEquals("at full size it would wrap", 2, atFullSize(collectives, fontScale = 1f).lineCount)
        TopLevelTab.entries.forEach(::assertWholeOnOneLine)
        val share = shareOfDefaultSize(collectives)
        assertTrue("Collectives shrank, but not past the smallest: $share", share < 1f && share >= SMALLEST_TAB_LABEL)
    }

    @Test
    @Config(qualifiers = "w240dp-h600dp-xxhdpi")
    fun aLabelThatWouldShrinkPastTheSmallestIsCutShortInstead() {
        showBar()

        val collectives = label(TopLevelTab.Collectives)
        assertEquals(1, collectives.lineCount)
        assertTrue("cut short", collectives.isLineEllipsized(0))
    }
}
