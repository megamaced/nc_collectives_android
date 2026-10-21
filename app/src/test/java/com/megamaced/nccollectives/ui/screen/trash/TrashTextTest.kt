package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the two trash screens say, and what deleting forever asks first. */
@RunWith(AndroidJUnit4::class)
class TrashTextTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aTrashedPageNamesWhoLastChangedIt_andDeletingItForeverAsksByTitle() {
        rule.setContent { PageTrash(PaddingValues(), RemoteListUiState(items = listOf(page())), {}, {}, {}, {}, {}) }

        rule.onNodeWithText("Trash").assertExists()
        rule.onNodeWithText("By Priya").assertExists()
        rule.onNodeWithContentDescription("Delete forever").performClick()
        rule.onNodeWithText("Permanently delete?").assertExists()
        rule.onNodeWithText("\"Plans\" will be deleted forever. This can't be undone.").assertExists()
    }

    @Test
    fun anEmptyPageTrashSaysWhatWillAppearThere() {
        rule.setContent { PageTrash(PaddingValues(), RemoteListUiState(), {}, {}, {}, {}, {}) }

        rule.onNodeWithText("Trash is empty").assertExists()
        rule.onNodeWithText("Pages you delete from this collective will appear here.").assertExists()
    }

    @Test
    fun deletingACollectiveForeverAsksByName_andSaysWhatGoesWithIt() {
        rule.setContent { CollectiveTrash(PaddingValues(), RemoteListUiState(items = listOf(collective())), {}, {}, {}, {}, {}) }

        rule.onNodeWithText("Collective trash").assertExists()
        rule.onNodeWithContentDescription("Delete forever").performClick()
        rule.onNodeWithText("Permanently delete \"Wiki\"?").assertExists()
        rule
            .onNodeWithText(
                "This will permanently delete the collective and tear down the underlying Team. " +
                    "All pages will be lost forever. This can't be undone.",
            ).assertExists()
    }

    @Test
    fun anEmptyCollectiveTrashSaysWhatWillAppearThere() {
        rule.setContent { CollectiveTrash(PaddingValues(), RemoteListUiState(), {}, {}, {}, {}, {}) }

        rule.onNodeWithText("Trash is empty").assertExists()
        rule.onNodeWithText("Collectives you move to trash will appear here.").assertExists()
    }

    private fun page() =
        Page(
            id = 12,
            collectiveId = 3,
            parentId = 1,
            title = "Plans",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            isFullWidth = false,
            trashed = true,
            serverTimestamp = 0,
            size = 0,
            fileName = "Plans.md",
            filePath = "",
            collectivePath = "Wiki",
            linkedPageIds = emptyList(),
            lastUserDisplayName = "Priya",
            bodyMd = null,
            bodyTimestamp = null,
            bodyEtag = null,
            bodyFromQueue = false,
            draftBodyMd = null,
        )

    private fun collective() =
        Collective(
            id = 3,
            name = "Wiki",
            slug = null,
            emoji = null,
            circleId = null,
            canEdit = true,
            canShare = true,
            level = 0,
            userShowMembers = true,
            pageOrder = PageOrder.Custom,
            isPageShare = false,
            trashed = true,
            favoritePageIds = emptySet(),
        )
}
