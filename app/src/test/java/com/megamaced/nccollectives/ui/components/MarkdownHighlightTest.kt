package com.megamaced.nccollectives.ui.components

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nextcloud Text's `==text==` reaches a rendered page as a highlight. It is
 * rewritten to `<mark>`, which the renderer has to draw itself: without a
 * handler for the tag, the text comes out plain.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownHighlightTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var background = 0
    private var foreground = 0
    private var themeMode by mutableStateOf(ThemeMode.Light)

    private fun show(markdown: String) {
        rule.setContent {
            NcCollectivesTheme(themeMode = themeMode, dynamicColor = false) {
                background = MaterialTheme.colorScheme.tertiaryContainer.toArgb()
                foreground = MaterialTheme.colorScheme.onTertiaryContainer.toArgb()
                MarkdownView(markdown)
            }
        }
    }

    private fun rendered(): Spanned {
        rule.waitForIdle()
        return textViewIn(rule.activity.window.decorView)!!.text as Spanned
    }

    private fun textViewIn(view: View): TextView? =
        when (view) {
            is TextView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { textViewIn(view.getChildAt(it)) }
            else -> null
        }

    private fun Spanned.covered(span: Any): String = subSequence(getSpanStart(span), getSpanEnd(span)).toString()

    @Test
    fun aHighlightIsDrawnBehindItsText_andOnlyThere() {
        show("Spot them on a ==dry, still morning== in October.")
        val text = rendered()

        val backgrounds = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
        assertEquals(listOf("dry, still morning" to background), backgrounds.map { text.covered(it) to it.backgroundColor })
        val foregrounds = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        assertEquals(listOf("dry, still morning" to foreground), foregrounds.map { text.covered(it) to it.foregroundColor })
    }

    @Test
    fun aHighlightTakesItsColoursFromTheTheme_inDarkToo() {
        show("A ==marked== word.")
        val light = rendered().let { it.getSpans(0, it.length, BackgroundColorSpan::class.java).single().backgroundColor }

        themeMode = ThemeMode.Dark
        val text = rendered()

        val dark = text.getSpans(0, text.length, BackgroundColorSpan::class.java).single()
        assertEquals(background, dark.backgroundColor)
        assertNotEquals("the dark theme's own colour, not the light one", light, dark.backgroundColor)
        assertEquals(foreground, text.getSpans(0, text.length, ForegroundColorSpan::class.java).single().foregroundColor)
    }

    @Test
    fun aHighlightKeepsTheFormattingInsideIt() {
        show("A ==**bold** choice== here.")
        val text = rendered()

        assertEquals("bold choice", text.covered(text.getSpans(0, text.length, BackgroundColorSpan::class.java).single()))
        val bold = text.getSpans(0, text.length, StrongEmphasisSpan::class.java)
        assertEquals(listOf("bold"), bold.map { text.covered(it) })
    }
}
