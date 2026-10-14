package com.megamaced.nccollectives.ui.screen.page

import com.megamaced.nccollectives.domain.model.Page
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShownVersionsTest {
    private fun page(
        bodyMd: String? = "# body",
        bodyTimestamp: Long? = 100L,
        bodyFromQueue: Boolean = false,
    ) = Page(
        id = 1,
        collectiveId = 1,
        parentId = 0,
        title = "Page",
        emoji = null,
        tags = emptyList(),
        subpageOrder = emptyList(),
        isFullWidth = false,
        trashed = false,
        serverTimestamp = 0,
        size = 0,
        fileName = "Page.md",
        filePath = "",
        collectivePath = "",
        linkedPageIds = emptyList(),
        lastUserDisplayName = "",
        bodyMd = bodyMd,
        bodyTimestamp = bodyTimestamp,
        bodyFromQueue = bodyFromQueue,
        draftBodyMd = null,
    )

    @Test
    fun nothingIsShownUntilThereIsABody() =
        runTest {
            val shown = flowOf(null, page(bodyMd = null)).shownVersions().toList()
            assertEquals(emptyList<Long?>(), shown)
        }

    @Test
    fun aCachedBodyThenTheRefreshedOneAreTwoViews() =
        runTest {
            val shown = flowOf(page(bodyTimestamp = 100L), page(bodyTimestamp = 200L)).shownVersions().toList()
            assertEquals(listOf<Long?>(100L, 200L), shown)
        }

    @Test
    fun anUnknownVersionThenTheFetchedOneAreTwoViews() =
        runTest {
            // After the upgrade: the cached body predates stored versions, so
            // the screen first shows it unknown, then the whole fetch says.
            val shown = flowOf(page(bodyTimestamp = null), page(bodyTimestamp = 300L)).shownVersions().toList()
            assertEquals(listOf(null, 300L), shown)
        }

    @Test
    fun theUsersQueuedTextShowsNoServerVersion() =
        runTest {
            // The row still holds version 100 from the server, but the screen
            // shows the user's own queued edit over it.
            val shown = flowOf(page(bodyTimestamp = 100L, bodyFromQueue = true)).shownVersions().toList()
            assertEquals(listOf<Long?>(null), shown)
        }

    @Test
    fun theSameVersionAgainIsTheSameView() =
        runTest {
            val shown = flowOf(page(bodyTimestamp = 100L), page(bodyMd = "# body", bodyTimestamp = 100L)).shownVersions().toList()
            assertEquals(listOf<Long?>(100L), shown)
        }
}
