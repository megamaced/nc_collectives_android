package com.megamaced.nccollectives.ui.screen.collective

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Pins the sibling title ordering [buildVisibleNodes] falls back to when a
 * parent has no server `subpageOrder`.
 *
 * It is the web app's title order — a numeric `localeCompare` — so these
 * cases fix: case-insensitive at first glance, accents collated beside their
 * base letter, numbers by value, and a fixed place for titles that differ
 * only by case. The collator follows the default locale, so the tests pin
 * it to English, and it is Android's ICU (`android.icu.text`), so they run
 * under Robolectric.
 *
 * The ordering also has to survive being applied to an *unsorted* list:
 * `buildVisibleNodes` is pure and cannot assume its caller handed it the
 * DAO's `ORDER BY title COLLATE NOCASE ASC` output.
 */
@RunWith(AndroidJUnit4::class)
class PageTreeTitleOrderTest {
    private val originalLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

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
    fun nonAsciiTitles_collateBesideTheirBaseLetter() {
        // As the web app's `localeCompare` does — an ordinal comparison put
        // "Éclair" after every ASCII letter.
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "Éclair"),
                page(id = 2, parentId = 100, title = "Zebra"),
                page(id = 3, parentId = 100, title = "apple"),
            ),
        )
        assertEquals(listOf("apple", "Éclair", "Zebra"), out)
    }

    @Test
    fun numberedTitles_orderByValue() {
        val out = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "Page 10"),
                page(id = 2, parentId = 100, title = "Page 2"),
                page(id = 3, parentId = 100, title = "Page 1"),
            ),
        )
        assertEquals(listOf("Page 1", "Page 2", "Page 10"), out)
    }

    @Test
    fun titlesDifferingOnlyByCase_haveAFixedPlace_whateverTheInputOrder() {
        // The rows are LazyColumn keys, so they must not swap places between
        // flattens. The collator breaks the tie on case — lower case first
        // in English — so the order no longer depends on the input order.
        val upperFirst = titlesUnder(
            listOf(
                page(id = 1, parentId = 100, title = "ITEM"),
                page(id = 2, parentId = 100, title = "item"),
            ),
        )
        val lowerFirst = titlesUnder(
            listOf(
                page(id = 2, parentId = 100, title = "item"),
                page(id = 1, parentId = 100, title = "ITEM"),
            ),
        )
        assertEquals(listOf("item", "ITEM"), upperFirst)
        assertEquals(listOf("item", "ITEM"), lowerFirst)
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
