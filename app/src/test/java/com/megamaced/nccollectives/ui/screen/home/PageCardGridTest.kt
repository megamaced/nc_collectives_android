package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card grid's layout on a phone, two columns wide, inside a lazy list as
 * on Home — where a row has no height of its own to fill.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so a card's lines take room.
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class PageCardGridTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(
        pages: List<PageListItem>,
        caption: (PageListItem) -> String? = { null },
        supporting: (PageListItem) -> String? = { null },
    ) {
        rule.setContent {
            LazyColumn {
                item { PageCardGrid(pages = pages, onPageClick = {}, caption = caption, supporting = supporting) }
            }
        }
    }

    @Test
    fun aShortLastRowKeepsItsCardTheWidthOfTheOthers() {
        show(pages = listOf(listItem(1, title = "One"), listItem(2, title = "Two"), listItem(3, title = "Three")))

        val above = rule.onNodeWithText("One").getBoundsInRoot()
        val alone = rule.onNodeWithText("Three").getBoundsInRoot()
        assertEquals(above.left.value, alone.left.value, 0.5f)
        assertEquals(above.width.value, alone.width.value, 0.5f)
    }

    @Test
    @Config(fontScale = 2f)
    fun theCardsInARowShareTheTallestOnesHeight() {
        show(
            pages = listOf(
                listItem(1, title = "Short"),
                listItem(2, title = "A title long enough to take two lines"),
            ),
            caption = { page -> "Collective 1".takeIf { page.id == 2L } },
            supporting = { page -> "Alice · 2 hours ago".takeIf { page.id == 2L } },
        )

        val short = rule.onNodeWithText("Short").getBoundsInRoot()
        val tall = rule.onNodeWithText("A title long enough to take two lines").getBoundsInRoot()
        assertTrue("the tall card outgrows the minimum", tall.height > 132.dp)
        assertEquals(tall.height.value, short.height.value, 0.5f)
    }
}
