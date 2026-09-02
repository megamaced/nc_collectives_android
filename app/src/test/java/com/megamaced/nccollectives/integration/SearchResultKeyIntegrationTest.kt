package com.megamaced.nccollectives.integration

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.ui.screen.search.searchHitKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #38: a duplicate key in a lazy layout is not a cosmetic problem —
 * Compose throws, and the search screen goes with it.
 *
 * The old key was `pageId ?: title.hashCode()`. Reading it, the fallback
 * looks like a reasonable identity; the failure is in Compose's reaction to
 * a repeat, which is why this is rendered through a real `LazyColumn` rather
 * than asserted as string equality. The list below is precisely what
 * `SearchRepositoryImpl` produces when a server entry carries neither a
 * fileId attribute nor a usable resource URL, which it documents as allowed.
 */
@RunWith(AndroidJUnit4::class)
class SearchResultKeyIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoUnresolvedHitsSharingATitle_bothRender() =
        renders(
            listOf(
                SearchHit(title = "Meeting notes", snippet = "in Team", pageId = null, collectiveId = 1),
                SearchHit(title = "Meeting notes", snippet = "in Personal", pageId = null, collectiveId = 2),
            ),
        ) {
            assertEquals(2, compose.onAllNodesWithText("Meeting notes").fetchSemanticsNodes().size)
        }

    @Test
    fun aHitWhoseTitleHashCollidesWithAnothers_bothRender() =
        renders(
            // "Aa" and "BB" hash to the same value in Java, which is what made
            // the old fallback unsafe even for distinct titles.
            listOf(
                SearchHit(title = "Aa", snippet = null, pageId = null, collectiveId = 1),
                SearchHit(title = "BB", snippet = null, pageId = null, collectiveId = 1),
            ),
        ) {
            compose.onNodeWithText("Aa").assertIsDisplayed()
            compose.onNodeWithText("BB").assertIsDisplayed()
        }

    @Test
    fun aResolvedHitAndAnUnresolvedOneWhoseIdMatchesTheOthersTitleHash_bothRender() =
        renders(
            // The old key mixed namespaces: a resolved hit contributed a raw
            // `Long` and an unresolved one a raw `Int` hash, so a page id that
            // happened to equal another title's hash collided across the two
            // shapes. Prefixing the id keeps them apart.
            listOf(
                SearchHit(title = "Resolved", snippet = null, pageId = "Unresolved".hashCode().toLong(), collectiveId = 1),
                SearchHit(title = "Unresolved", snippet = null, pageId = null, collectiveId = 1),
            ),
        ) {
            compose.onNodeWithText("Resolved").assertIsDisplayed()
            compose.onNodeWithText("Unresolved").assertIsDisplayed()
        }

    @Test
    fun aResolvedHitKeepsTheSameKeyAcrossReEmissions() {
        // Position must not be part of a resolved hit's identity, or a result
        // that moves up the list is treated as a new item and loses its
        // scroll position and any animation state.
        val hit = SearchHit(title = "Stable", snippet = null, pageId = 42, collectiveId = 1)

        assertEquals(searchHitKey(0, hit), searchHitKey(3, hit))
    }

    @Test
    fun anUnresolvedHitIsDistinguishedByPosition() {
        val hit = SearchHit(title = "Same", snippet = null, pageId = null, collectiveId = 1)

        assertEquals("hit-0-Same", searchHitKey(0, hit))
        assertEquals("hit-1-Same", searchHitKey(1, hit))
    }

    private fun renders(
        hits: List<SearchHit>,
        assertions: () -> Unit,
    ) {
        compose.setContent {
            LazyColumn {
                itemsIndexed(hits, key = ::searchHitKey) { _, hit -> Text(hit.title) }
            }
        }
        assertions()
    }
}
