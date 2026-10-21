package com.megamaced.nccollectives.ui.screen.page

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Words a page's screens build from what they know. */
@RunWith(AndroidJUnit4::class)
class PageTextTest {
    @Test
    fun theLastChangedLineSaysWhoAndWhen_asMuchAsIsKnown() {
        assertEquals("Last changed by Ada · 5 min. ago", lastChangedLine("Ada", "5 min. ago")?.english())
        assertEquals("Last changed by Ada", lastChangedLine("Ada", null)?.english())
        assertEquals("Last changed · 5 min. ago", lastChangedLine(null, "5 min. ago")?.english())
        assertNull(lastChangedLine(null, null))
    }

    @Test
    fun eachAttachmentStateHasItsOwnWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            listOf("Tap to insert", "Waiting to upload", "Uploading…", "Upload failed"),
            listOf(Attachment.Status.REMOTE, Attachment.Status.PENDING, Attachment.Status.UPLOADING, Attachment.Status.FAILED)
                .map { context.getString(it.pickerLabel()) },
        )
    }
}
