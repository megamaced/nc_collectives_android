package com.megamaced.nccollectives.ui.screen.collective

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.prefs.CollectiveSort
import com.megamaced.nccollectives.domain.model.PageOrder
import com.megamaced.nccollectives.ui.components.SortHeader
import com.megamaced.nccollectives.ui.components.SubpageChipRow
import com.megamaced.nccollectives.ui.screen.home.listItem
import com.megamaced.nccollectives.ui.screen.members.TruncationNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Wording shared by the collective list, the page tree and a page's sections. */
@RunWith(AndroidJUnit4::class)
class CollectiveScreensTextTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theCollectiveSortAndThePageOrderNameTheirOptionsAlike() {
        assertEquals(
            listOf("A–Z", "Z–A", "Newest first", "Oldest first"),
            CollectiveSort.entries.map { context.getString(collectiveSortLabel(it)) },
        )
        assertEquals(
            listOf("Custom order", "A–Z", "Z–A", "Newest first", "Oldest first"),
            PageOrder.entries.map { context.getString(pageOrderLabel(it)) },
        )
    }

    @Test
    fun theSortButtonIsNamedForScreenReaders_andItsMenuTicksTheOrderInUse() {
        rule.setContent {
            SortHeader(
                title = "Pages",
                selected = PageOrder.NewestFirst,
                options = PageOrder.entries,
                label = ::pageOrderLabel,
                onSelect = {},
            )
        }

        rule.onNodeWithContentDescription("Sort order").assertExists()
        rule.onNodeWithText("Newest first").performClick()
        rule.onNodeWithText("Custom order").assertExists()
        rule.onNodeWithContentDescription("Selected").assertExists()
    }

    @Test
    fun aPageSectionCountsItsPages_andSaysWhetherItWillOpenOrClose() {
        rule.setContent { SubpageChipRow(pages = listOf(listItem(1), listItem(2)), onOpenPage = {}) }

        rule.onNodeWithText("Subpages (2)").assertExists()
        rule.onNodeWithContentDescription("Expand").performClick()
        rule.onNodeWithContentDescription("Collapse").assertExists()
    }

    @Test
    fun aCutShortMembersListSaysHowManyItShows() {
        rule.setContent { TruncationNotice(shown = 50) }

        rule.onNodeWithText("Showing the first 50 members. The server doesn't say how many more there are.").assertExists()
    }
}
