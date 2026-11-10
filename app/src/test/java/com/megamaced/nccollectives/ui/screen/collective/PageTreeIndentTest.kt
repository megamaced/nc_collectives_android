package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #75: subpages are indented, but only slightly — a phone has little
 * width to spare — and a deep page stops moving right after three levels.
 */
@RunWith(AndroidJUnit4::class)
class PageTreeIndentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theIndentIsEightDpALevel_andStopsAfterThreeLevels() {
        assertEquals(0.dp, subpageIndent(0))
        assertEquals(8.dp, subpageIndent(1))
        assertEquals(24.dp, subpageIndent(3))
        assertEquals(24.dp, subpageIndent(7))
    }

    @Test
    fun aRowSitsFurtherRightOnlyByItsIndent() {
        rule.setContent {
            Column {
                row(id = 1, title = "Top", depth = 0)
                row(id = 2, title = "Two down", depth = 2)
                row(id = 3, title = "Five down", depth = 5)
            }
        }

        // The title's own node: the clickable row merges it, and the row
        // itself spans the full width at every depth.
        fun titleLeft(title: String) = rule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().left
        val top = titleLeft("Top")
        assertEquals(16.dp, titleLeft("Two down") - top)
        assertEquals(24.dp, titleLeft("Five down") - top)
    }

    @Test
    fun collapseAllIsOfferedOnlyWhileAFolderOnScreenIsOpen() {
        val folder = node(id = 1, hasChildren = true)
        val leaf = node(id = 2, hasChildren = false)

        assertFalse(canCollapseAll(listOf(folder, leaf), expanded = emptySet()))
        assertTrue(canCollapseAll(listOf(folder, leaf), expanded = setOf(1)))
        // An id left in the set for a page that isn't a folder on screen
        // (removed, or now childless) has nothing to close.
        assertFalse(canCollapseAll(listOf(folder, leaf), expanded = setOf(2, 99)))
    }

    @androidx.compose.runtime.Composable
    private fun row(
        id: Long,
        title: String,
        depth: Int,
    ) = PageTreeItem(
        node = node(id = id, title = title, depth = depth),
        isExpanded = false,
        nowMillis = 0,
        onToggle = {},
        onOpen = {},
        onToggleFavorite = {},
        onAddSubpage = {},
    )

    private fun node(
        id: Long,
        title: String = "Page $id",
        depth: Int = 0,
        hasChildren: Boolean = false,
    ) = PageNode(
        page = PageListItem(
            id = id,
            collectiveId = 1,
            parentId = 10,
            title = title,
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            trashed = false,
            serverTimestamp = 0,
            lastUserDisplayName = "",
            hasDraft = false,
        ),
        hasChildren = hasChildren,
        isFavorite = false,
        depth = depth,
    )
}
