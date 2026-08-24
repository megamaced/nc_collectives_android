package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the sibling title ordering [buildVisibleNodes] falls back to when a
 * parent has no server `subpageOrder`.
 *
 * R-44 swapped `sortedBy { it.title.lowercase() }` — which allocated a
 * lowercased copy of every title on every comparison — for a shared
 * `String.CASE_INSENSITIVE_ORDER` comparator. That is meant to be an
 * allocation change only, so these cases fix the observable ordering:
 * case-insensitive, ordinal (**not** locale-collated) for non-ASCII, and
 * stable for titles that differ only by case.
 *
 * The ordering also has to survive being applied to an *unsorted* list:
 * `buildVisibleNodes` is pure and cannot assume its caller handed it the
 * DAO's `ORDER BY title COLLATE NOCASE ASC` output.
 */
class PageTreeTitleOrderTest {
    private fun page(
        id: Long,
        parentId: Long,
        title: String,
        subpageOrder: List<Long> = emptyList(),
    ) = PageListItem(
        id = id,
        collectiveId = 1,
        parentId = parentId,
        title = title,
        emoji = null,
        tags = emptyList(),
        subpageOrder = subpageOrder,
        trashed = false,
        serverTimestamp = 0,
        lastUserDisplayName = "",
        hasDraft = false,
    )

    private fun titlesUnder(children: List<PageListItem>): List<String> {
        val pages = listOf(page(id = 100, parentId = 0, title = "Landing")) + children
        return buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = emptySet())
            .map { it.page.title }
    }

    @Test
    fun mixedCaseTitles_sortCaseInsensitively() {
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "banana"),
                page(id = 2, parentId = 100, title = "Apple"),
                page(id = 3, parentId = 100, title = "cherry"),
            ),
        )
        assertEquals(listOf("Apple", "banana", "cherry"), out)
    }

    @Test
    fun unsortedInput_isSortedByTheFallback() {
        // Deliberately reverse order in — the pure function must not rely
        // on the DAO having pre-sorted its input.
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "Zulu"),
                page(id = 2, parentId = 100, title = "Mike"),
                page(id = 3, parentId = 100, title = "Alpha"),
            ),
        )
        assertEquals(listOf("Alpha", "Mike", "Zulu"), out)
    }

    @Test
    fun nonAsciiTitles_orderByCodePoint_notLocaleCollation() {
        // Documented, not aspirational: neither the old `lowercase()` sort
        // nor the new comparator collates accents next to their base
        // letter, so "Éclair" sorts after every ASCII letter.
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "Éclair"),
                page(id = 2, parentId = 100, title = "Zebra"),
                page(id = 3, parentId = 100, title = "apple"),
            ),
        )
        assertEquals(listOf("apple", "Zebra", "Éclair"), out)
    }

    @Test
    fun titlesDifferingOnlyByCase_keepInputOrder() {
        // Equal sort keys — the sort is stable, so the two rows must not
        // swap places between flattens (they are LazyColumn keys).
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "ITEM"),
                page(id = 2, parentId = 100, title = "item"),
            ),
        )
        assertEquals(listOf("ITEM", "item"), out)
    }

    @Test
    fun subpageOrderStillWins_titleOrderOnlyFillsTheRest() {
        val landing = page(id = 100, parentId = 0, title = "Landing", subpageOrder = listOf(3))
        val pages = listOf(
            landing,
            page(id = 1, parentId = 100, title = "zulu"),
            page(id = 2, parentId = 100, title = "Alpha"),
            page(id = 3, parentId = 100, title = "Mike"),
        )
        val out = buildVisibleNodes(pages, expanded = emptySet(), favoriteIds = emptySet())
            .map { it.page.title }
        assertEquals(listOf("Mike", "Alpha", "zulu"), out)
    }
}
