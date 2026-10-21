package com.megamaced.nccollectives.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the conflict banner says, and what discarding the draft asks first. */
@RunWith(AndroidJUnit4::class)
class ConflictBannerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theBannerSaysTheDraftIsKept_andOffersCopyDiscardAndReplace() {
        rule.setContent { ConflictBanner(draft = "my edits", onReplace = {}, onDiscard = {}) }

        rule.onNodeWithText("Unsaved changes on this device").assertExists()
        rule
            .onNodeWithText("The server's version changed after you started editing. Your local edits are kept as a draft.")
            .assertExists()
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Replace page").assertExists()
        rule.onNodeWithText("Discard").performClick()

        rule.onNodeWithText("Discard your draft?").assertExists()
        rule
            .onNodeWithText(
                "Your unsaved edits will be deleted and the server's version kept. " +
                    "This can't be undone — use Copy first if you want to keep them.",
            ).assertExists()
        rule.onNodeWithText("Keep draft").assertExists()
    }
}
