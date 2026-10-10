package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins [orderByLastEdited], the collective list's "Last edited" order. */
class CollectiveListOrderTest {
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

    // Name order, as the DAO hands it over.
    private val byName = listOf(
        collective(id = 1, name = "Archive"),
        collective(id = 2, name = "Recipes"),
        collective(id = 3, name = "Wiki"),
    )

    @Test
    fun newestPageFirst() {
        val ordered = orderByLastEdited(byName, mapOf(1L to 100L, 2L to 300L, 3L to 200L))

        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
    }

    @Test
    fun collectivesWithNothingCachedGoLastInNameOrder() {
        val ordered = orderByLastEdited(byName, mapOf(3L to 200L))

        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.id })
    }

    @Test
    fun aTieKeepsNameOrder() {
        val ordered = orderByLastEdited(byName, mapOf(1L to 100L, 2L to 100L, 3L to 100L))

        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
    }
}
