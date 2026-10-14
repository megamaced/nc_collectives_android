package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultParentIdTest {
    private fun page(
        id: Long,
        parentId: Long,
    ) = PageListItem(
        id = id,
        collectiveId = 1,
        parentId = parentId,
        title = "Page $id",
        emoji = null,
        tags = emptyList(),
        subpageOrder = emptyList(),
        trashed = false,
        serverTimestamp = 0,
        lastUserDisplayName = "",
        hasDraft = false,
    )

    @Test
    fun theLandingPageIsTheDefault_whereverItSits() {
        assertEquals(10L, defaultParentId(listOf(page(3, parentId = 10), page(10, parentId = 0))))
    }

    @Test
    fun withNoLandingPageTheFirstChoiceIs() {
        assertEquals(3L, defaultParentId(listOf(page(3, parentId = 10), page(4, parentId = 10))))
    }

    @Test
    fun noChoicesIsNoDefault() {
        assertNull(defaultParentId(emptyList()))
    }
}
