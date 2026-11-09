package com.megamaced.nccollectives.ui.screen.search

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #85: a search result either opens its page or says why it can't.
 * "Not in your local cache" is only true of a page the app identified but
 * hasn't downloaded; a result it couldn't identify says so instead.
 */
@RunWith(AndroidJUnit4::class)
class SearchHitRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    private fun show(hit: SearchHit) {
        rule.setContent { SearchHitRow(hit = hit, onClick = { clicks++ }) }
    }

    @Test
    fun aCachedPage_opensAndCarriesNoReason() {
        show(SearchHit(title = "Notes", snippet = null, pageId = 1, collectiveId = 2))

        rule
            .onNodeWithText("Notes")
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, clicks)
        rule.onNodeWithText(context.getString(R.string.search_not_cached)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.search_not_openable)).assertDoesNotExist()
    }

    @Test
    fun anIdentifiedPageNotOnTheDevice_saysSo_andDoesNotOpen() {
        show(SearchHit(title = "Notes", snippet = null, pageId = 1, collectiveId = null))

        rule.onNodeWithText(context.getString(R.string.search_not_cached)).assertExists()
        rule.onNodeWithText(context.getString(R.string.search_not_openable)).assertDoesNotExist()
        rule.onNodeWithText("Notes").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun aResultWithNoPageId_saysItCantBeOpened_notThatItIsUncached() {
        show(SearchHit(title = "Notes", snippet = null, pageId = null, collectiveId = null))

        rule.onNodeWithText(context.getString(R.string.search_not_openable)).assertExists()
        rule.onNodeWithText(context.getString(R.string.search_not_cached)).assertDoesNotExist()
        rule.onNodeWithText("Notes").assertIsNotEnabled()
    }
}
