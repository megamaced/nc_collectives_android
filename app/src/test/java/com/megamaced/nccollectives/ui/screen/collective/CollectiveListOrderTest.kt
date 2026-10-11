package com.megamaced.nccollectives.ui.screen.collective

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.prefs.CollectiveSort
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Pins [orderCollectives], the collective list's sort orders.
 *
 * Under Robolectric because name order is Android's ICU collator
 * (`android.icu.text`), which a plain JVM test only has as stubs.
 */
@RunWith(AndroidJUnit4::class)
class CollectiveListOrderTest {
    private val originalLocale = Locale.getDefault()

    // Name order follows the default locale's collator.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.ENGLISH)

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun collective(
        id: Long,
        name: String,
    ) = Collective(
        id = id,
        name = name,
        slug = null,
        emoji = null,
        circleId = null,
        canEdit = true,
        canShare = true,
        level = 0,
        userShowMembers = true,
        pageOrder = PageOrder.Custom,
        isPageShare = false,
        trashed = false,
        favoritePageIds = emptySet(),
    )

    // Deliberately not in name order: the function must not rely on the DAO's.
    private val collectives = listOf(
        collective(id = 1, name = "Wiki"),
        collective(id = 2, name = "Archive"),
        collective(id = 3, name = "Recipes"),
    )

    private fun ids(
        lastEditedAt: Map<Long, Long>,
        sort: CollectiveSort,
        list: List<Collective> = collectives,
    ) = orderCollectives(list, lastEditedAt, sort).map { it.id }

    // The sort menu lists `entries`, in the page order menu's arrangement.
    @Test
    fun entriesAreInMenuOrder() {
        assertEquals(
            listOf(
                CollectiveSort.NameAscending,
                CollectiveSort.NameDescending,
                CollectiveSort.NewestFirst,
                CollectiveSort.OldestFirst,
            ),
            CollectiveSort.entries,
        )
    }

    @Test
    fun storedNamesReadBack() {
        CollectiveSort.entries.forEach { assertEquals(it, CollectiveSort.fromStored(it.name)) }
        // What v2.13.0 stored keeps meaning the same order.
        assertEquals(CollectiveSort.NameAscending, CollectiveSort.fromStored("Name"))
        assertEquals(CollectiveSort.NewestFirst, CollectiveSort.fromStored("LastEdited"))
        assertEquals(CollectiveSort.NameAscending, CollectiveSort.fromStored(null))
        assertEquals(CollectiveSort.NameAscending, CollectiveSort.fromStored("SomethingNewer"))
    }

    @Test
    fun nameOrders() {
        assertEquals(listOf(2L, 3L, 1L), ids(emptyMap(), CollectiveSort.NameAscending))
        assertEquals(listOf(1L, 3L, 2L), ids(emptyMap(), CollectiveSort.NameDescending))
    }

    // The same collation as page titles: numbers by value, accents beside
    // their base letter, case ignored.
    @Test
    fun namesSortAsPageTitlesDo() {
        val names = listOf(
            collective(id = 1, name = "Team 10"),
            collective(id = 2, name = "team 2"),
            collective(id = 3, name = "Zebra"),
            collective(id = 4, name = "Éclair"),
        )

        assertEquals(listOf(4L, 2L, 1L, 3L), ids(emptyMap(), CollectiveSort.NameAscending, names))
    }

    @Test
    fun timeOrdersGoByTheNewestCachedPage() {
        val lastEditedAt = mapOf(1L to 100L, 2L to 300L, 3L to 200L)

        assertEquals(listOf(2L, 3L, 1L), ids(lastEditedAt, CollectiveSort.NewestFirst))
        assertEquals(listOf(1L, 3L, 2L), ids(lastEditedAt, CollectiveSort.OldestFirst))
    }

    @Test
    fun collectivesWithNothingCachedGoLastUnderBothTimeOrders() {
        val lastEditedAt = mapOf(1L to 200L)

        assertEquals(listOf(1L, 2L, 3L), ids(lastEditedAt, CollectiveSort.NewestFirst))
        assertEquals(listOf(1L, 2L, 3L), ids(lastEditedAt, CollectiveSort.OldestFirst))
    }

    @Test
    fun aTimeTieFallsBackToName() {
        val lastEditedAt = mapOf(1L to 100L, 2L to 100L, 3L to 100L)

        assertEquals(listOf(2L, 3L, 1L), ids(lastEditedAt, CollectiveSort.NewestFirst))
        assertEquals(listOf(2L, 3L, 1L), ids(lastEditedAt, CollectiveSort.OldestFirst))
    }
}
