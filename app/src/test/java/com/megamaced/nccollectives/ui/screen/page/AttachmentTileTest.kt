package com.megamaced.nccollectives.ui.screen.page

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file kept in the page's own folder can't be deleted through the
 * attachments API — the server only deletes from `.attachments.<pageId>/` —
 * so its tile offers no Delete that would end in a 404.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentTileTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deleteLabel = context.getString(R.string.action_delete)
    private var deletes = 0

    private fun show(inPageFolder: Boolean) {
        rule.setContent {
            AttachmentTile(
                attachment = Attachment(
                    id = "41/plan.pdf",
                    pageId = 41,
                    fileName = "plan.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 10,
                    lastModifiedMs = 0,
                    status = Attachment.Status.REMOTE,
                    remoteUrl = null,
                    localUriString = null,
                    inPageFolder = inPageFolder,
                ),
                onOpen = {},
                onDelete = { deletes++ },
                onRetry = {},
            )
        }
    }

    @Test
    fun anAttachment_canBeDeleted() {
        show(inPageFolder = false)

        rule.onNodeWithContentDescription(deleteLabel).performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun aPageFolderFile_offersNoDelete() {
        show(inPageFolder = true)

        rule.onNodeWithContentDescription(deleteLabel).assertDoesNotExist()
    }
}
