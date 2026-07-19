package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.domain.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [buildVisibleNodes].
 *
 * The headline case is issue #2 ("crashes when opening a subpage tree
 * with many pages"): a server-supplied `subpageOrder` containing a
 * duplicate id must not emit the same page twice, because each emitted
 * `page.id` is a `LazyColumn` key and Compose crashes on a duplicate.
 * The rest pin the ordering + expand/collapse contract so the dedup
 * guard doesn't regress normal trees.
 */
class PageTreeNodesTest {
    private fun page(
        id: Long,
        parentId: Long,
        title: String = "Page $id",
        subpageOrder: List<Long> = emptyList(),
    ) = Page(
        id = id,
        collectiveId = 1,
        parentId = parentId,
        title = title,
        emoji = null,
        tags = emptyList(),
        subpageOrder = subpageOrder,
        isFullWidth = false,
        trashed = false,
        serverTimestamp = 0,
        size = 0,
        fileName = "$title.md",
        filePath = "",
        collectivePath = ".Collectives/Wiki",
        linkedPageIds = emptyList(),
        lastUserDisplayName = "",
        bodyMd = null,
        draftBodyMd = null,
    )

    private fun ids(nodes: List<PageNode>) = nodes.map { it.page.id }

    @Test
    fun duplicateSubpageOrderId_emitsEachPageOnce() {
        // Landing page (parentId == 0) whose subpageOrder lists child 1
        // twice — the issue #2 crash trigger.
        val landing = page(id = 10, parentId = 0, subpageOrder = listOf(1, 1, 2, 3))
        val pages = listOf(
            landing,
            page(id = 1, parentId = 10),
            page(id = 2, parentId = 10),
            page(id = 3, parentId = 10),
        )

        val nodes = buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = emptySet())

        // Each child once, in subpageOrder — no duplicate key.
        assertEquals(listOf(1L, 2L, 3L), ids(nodes))
        assertEquals("no duplicate page ids", ids(nodes).size, ids(nodes).toSet().size)
    }

    @Test
    fun subpageOrderHonouredThenTitleFallback() {
        val landing = page(id = 10, parentId = 0, subpageOrder = listOf(3))
        val pages = listOf(
            landing,
            page(id = 1, parentId = 10, title = "Zebra"),
            page(id = 2, parentId = 10, title = "Apple"),
            page(id = 3, parentId = 10, title = "Mango"),
        )

        val nodes = buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = emptySet())

        // 3 is hinted first; the rest fall back to title order (Apple, Zebra).
        assertEquals(listOf(3L, 2L, 1L), ids(nodes))
    }

    @Test
    fun collapsedFolderHidesChildren_expandedShowsThem() {
        val pages = listOf(
            page(id = 10, parentId = 0),
            page(id = 1, parentId = 10, title = "Folder"),
            page(id = 2, parentId = 1, title = "Child"),
        )

        val collapsed = buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = emptySet())
        assertEquals(listOf(1L), ids(collapsed))
        assertEquals("folder row advertises children", true, collapsed.single().hasChildren)

        val expanded = buildVisibleNodes(pages, expanded = setOf(1L), favoriteIds = emptySet())
        assertEquals(listOf(1L, 2L), ids(expanded))
    }

    @Test
    fun noLandingPage_returnsEmpty() {
        // No parentId == 0 page → nothing to root the tree walk on.
        val pages = listOf(page(id = 1, parentId = 99))
        assertEquals(emptyList<Long>(), ids(buildVisibleNodes(pages, emptySet(), emptySet())))
    }

    @Test
    fun favoriteIds_markNodes() {
        val pages = listOf(
            page(id = 10, parentId = 0),
            page(id = 1, parentId = 10),
            page(id = 2, parentId = 10),
        )
        val nodes = buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = setOf(2L))
        assertEquals(false, nodes.first { it.page.id == 1L }.isFavorite)
        assertEquals(true, nodes.first { it.page.id == 2L }.isFavorite)
    }
}
