package com.megamaced.nccollectives.ui.screen.alerts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.NotifyLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three levels share the row's width equally. In the collective menu's
 * dialog on a narrow phone, or at a larger font size, a label that wrapped
 * made its segment taller than the other two.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so a label can wrap.
@Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class NotifyLevelChooserTest {
    @get:Rule
    val rule = createComposeRule()

    private val chosen = mutableListOf<NotifyLevel>()

    private val segment = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private var level by mutableStateOf(NotifyLevel.Off)

    private fun show() {
        rule.setContent {
            // About the width of an AlertDialog's content on that phone.
            Box(Modifier.width(280.dp)) {
                NotifyLevelChooser(title = "Wiki", level = level, onSelect = { chosen += it })
            }
        }
    }

    @Test
    fun theSegmentsStayOneHeight_inANarrowDialogAtALargerFont() {
        show()
        NotifyLevel.entries.forEach { chosenLevel ->
            level = chosenLevel
            rule.waitForIdle()
            val heights = rule
                .onAllNodes(segment)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot.height }
            assertEquals(3, heights.size)
            assertEquals("with $chosenLevel chosen: $heights", 1, heights.distinct().size)
        }
    }

    @Test
    fun aSegmentChoosesItsLevel() {
        show()

        rule.onNode(segment and hasText("All")).performClick()

        assertEquals(listOf(NotifyLevel.All), chosen)
    }
}
