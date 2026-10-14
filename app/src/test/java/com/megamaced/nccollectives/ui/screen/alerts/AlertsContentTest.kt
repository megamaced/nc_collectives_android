package com.megamaced.nccollectives.ui.screen.alerts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.AlertKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertsContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<Long>()
    private val dismissed = mutableListOf<Long>()

    private fun show(
        alerts: List<Alert>?,
        supported: Boolean? = true,
        newIds: Set<Long> = emptySet(),
    ) {
        rule.setContent {
            AlertsContent(
                alerts = alerts,
                supported = supported,
                newIds = newIds,
                nowMillis = 0,
                onOpen = { opened += it.id },
                onDismiss = { dismissed += it },
            )
        }
    }

    @Test
    fun aServerWithoutNotificationsSaysSo() {
        show(alerts = emptyList(), supported = false)

        rule.onNodeWithText("This server doesn't provide notifications.").assertExists()
    }

    @Test
    fun noAlertsSaysSo_butNothingIsDrawnBeforeTheCacheAnswers() {
        show(alerts = null)
        rule.onNodeWithText("No alerts").assertDoesNotExist()
    }

    @Test
    fun anEmptyListSaysSo() {
        show(alerts = emptyList())
        rule.onNodeWithText("No alerts").assertExists()
    }

    @Test
    fun newAlertsAreMarked_whetherStillUnseenOrSeenThisVisit() {
        show(
            alerts = listOf(alert(1), alert(2, seen = true), alert(3, seen = true)),
            newIds = setOf(2),
        )

        assertEquals(2, rule.onAllNodesWithContentDescription("New").fetchSemanticsNodes().size)
    }

    @Test
    fun aRowOpensItsAlert() {
        show(alerts = listOf(alert(1, subject = "Bob changed Soup")))

        rule.onNodeWithText("Bob changed Soup").performClick()
        assertEquals(listOf(1L), opened)
    }

    @Test
    fun aDeletedPageWithNoKnownCollectiveOpensNothing() {
        show(alerts = listOf(alert(1, kind = AlertKind.PageDeleted, collectiveId = null, subject = "Gone")))

        rule.onNodeWithText("Gone").assertIsNotEnabled()
    }

    @Test
    fun aDeletedPageWithItsCollectiveOpensIt() {
        show(alerts = listOf(alert(1, kind = AlertKind.PageDeleted, collectiveId = 7, subject = "Gone")))

        rule.onNodeWithText("Gone").assertIsEnabled().performClick()
        assertEquals(listOf(1L), opened)
    }

    @Test
    fun dismissingIsOfferedToAccessibilityServicesToo() {
        show(alerts = listOf(alert(1, subject = "Bob changed Soup")))

        val actions = rule.onNode(hasCustomAction("Dismiss")).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.single { it.label == "Dismiss" }.action() }
        assertEquals(listOf(1L), dismissed)
    }

    private fun hasCustomAction(label: String) =
        androidx.compose.ui.test.SemanticsMatcher("has custom action $label") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
        }
}
