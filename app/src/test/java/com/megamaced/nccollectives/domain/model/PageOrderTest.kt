package com.megamaced.nccollectives.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Pins [PageOrder]'s wire values to upstream's numbering
 * (`Collective::pageOrders` / `src/util/sortOrders.js`) and [orderSiblings]
 * to what the web app shows for each.
 *
 * Under Robolectric because title order is Android's ICU collator
 * (`android.icu.text`), which a plain JVM test only has as stubs.
 */
@RunWith(AndroidJUnit4::class)
class PageOrderTest {
    private val originalLocale = Locale.getDefault()

    // Title order follows the default locale's collator.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun page(
        id: Long,
        title: String,
        timestamp: Long = 0,
    ) = PageListItem(
        id = id,
        collectiveId = 1,
        parentId = 10,
        title = title,
        emoji = null,
        tags = emptyList(),
        subpageOrder = emptyList(),
        trashed = false,
        serverTimestamp = timestamp,
        lastUserDisplayName = "",
        hasDraft = false,
    )

    private val pages = listOf(
        page(id = 1, title = "Banana", timestamp = 200),
        page(id = 2, title = "apple", timestamp = 300),
        page(id = 3, title = "Cherry", timestamp = 100),
    )

    private fun ids(list: List<PageListItem>) = list.map { it.id }

    @Test
    fun wireValuesMatchUpstream() {
        assertEquals(PageOrder.Custom, PageOrder.fromWire(0))
        // Upstream calls this one `byTimeAsc`, but it puts the newest first.
        assertEquals(PageOrder.NewestFirst, PageOrder.fromWire(1))
        assertEquals(PageOrder.TitleAscending, PageOrder.fromWire(2))
        assertEquals(PageOrder.OldestFirst, PageOrder.fromWire(3))
        assertEquals(PageOrder.TitleDescending, PageOrder.fromWire(4))
    }

    // The sort menu lists `entries`: the default, the title pair, the time
    // pair. Declaration order, not wire order, is what the user sees.
    @Test
    fun entriesAreInMenuOrder() {
        assertEquals(
            listOf(
                PageOrder.Custom,
                PageOrder.TitleAscending,
                PageOrder.TitleDescending,
                PageOrder.NewestFirst,
                PageOrder.OldestFirst,
            ),
            PageOrder.entries,
        )
    }

    @Test
    fun unknownWireValueFallsBackToCustom() {
        assertEquals(PageOrder.Custom, PageOrder.fromWire(5))
        assertEquals(PageOrder.Custom, PageOrder.fromWire(-1))
    }

    @Test
    fun titleOrdersIgnoreCase() {
        assertEquals(listOf(2L, 1L, 3L), ids(orderSiblings(pages, emptyList(), PageOrder.TitleAscending)))
        assertEquals(listOf(3L, 1L, 2L), ids(orderSiblings(pages, emptyList(), PageOrder.TitleDescending)))
    }

    @Test
    fun timeOrdersSortOnServerTimestamp() {
        assertEquals(listOf(2L, 1L, 3L), ids(orderSiblings(pages, emptyList(), PageOrder.NewestFirst)))
        assertEquals(listOf(3L, 1L, 2L), ids(orderSiblings(pages, emptyList(), PageOrder.OldestFirst)))
    }

    @Test
    fun equalTimestampsFallBackToTitle() {
        val tied = listOf(
            page(id = 1, title = "Zebra", timestamp = 100),
            page(id = 2, title = "Apple", timestamp = 100),
        )

        assertEquals(listOf(2L, 1L), ids(orderSiblings(tied, emptyList(), PageOrder.NewestFirst)))
        assertEquals(listOf(2L, 1L), ids(orderSiblings(tied, emptyList(), PageOrder.OldestFirst)))
    }

    /**
     * Codex's review case: upstream compares titles with `{ numeric: true }`,
     * so "Page 2" sorts before "Page 10" in both directions.
     */
    @Test
    fun numberedTitlesSortByValue() {
        val numbered = listOf(
            page(id = 1, title = "Page 10"),
            page(id = 2, title = "Page 2"),
            page(id = 3, title = "Page 1"),
        )

        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleAscending)))
        assertEquals(listOf(1L, 2L, 3L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleDescending)))
    }

    @Test
    fun aLongerNumberSortsAfterAShorterOneWhateverItsDigits() {
        val numbered = listOf(
            page(id = 1, title = "Release 100"),
            page(id = 2, title = "Release 99"),
            page(id = 3, title = "Release 9"),
        )

        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleAscending)))
    }

    /**
     * Codex's second-round case: numeric collation covers every Unicode
     * decimal digit, not just ASCII — an ASCII-only rewrite left full-width
     * digits as text and sorted "Page 10" first.
     */
    @Test
    fun fullWidthDigitsSortByValue() {
        val numbered = listOf(
            page(id = 1, title = "Page 10"),
            page(id = 2, title = "Page \uFF12"),
            page(id = 3, title = "Page \uFF11"),
        )

        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleAscending)))
    }

    @Test
    fun arabicIndicDigitsSortByValue() {
        val numbered = listOf(
            page(id = 1, title = "\u0635\u0641\u062D\u0629 \u0661\u0660"),
            page(id = 2, title = "\u0635\u0641\u062D\u0629 \u0662"),
        )

        assertEquals(listOf(2L, 1L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleAscending)))
    }

    @Test
    fun leadingZerosDoNotChangeANumbersPlace() {
        val numbered = listOf(
            page(id = 1, title = "Week 10"),
            page(id = 2, title = "Week 09"),
        )

        assertEquals(listOf(2L, 1L), ids(orderSiblings(numbered, emptyList(), PageOrder.TitleAscending)))
    }

    @Test
    fun accentedTitlesCollateBesideTheirBaseLetter() {
        val accented = listOf(
            page(id = 1, title = "Zebra"),
            page(id = 2, title = "Éclair"),
            page(id = 3, title = "apple"),
        )

        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(accented, emptyList(), PageOrder.TitleAscending)))
    }

    @Test
    fun customOrderPutsHintedPagesFirstThenTitles() {
        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(pages, listOf(3L), PageOrder.Custom)))
    }

    @Test
    fun customOrderIgnoresARepeatedHint() {
        // Issue #2: a server subpageOrder can repeat an id.
        assertEquals(listOf(3L, 2L, 1L), ids(orderSiblings(pages, listOf(3L, 3L), PageOrder.Custom)))
    }

    @Test
    fun otherOrdersIgnoreTheCustomHint() {
        assertEquals(listOf(2L, 1L, 3L), ids(orderSiblings(pages, listOf(3L), PageOrder.TitleAscending)))
    }
}
