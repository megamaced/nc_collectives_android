package com.megamaced.nccollectives.ui.screen.settings

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.StartScreen
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.SyncStatus
import com.megamaced.nccollectives.data.prefs.TextScale
import com.megamaced.nccollectives.data.prefs.ThemeMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Issue #62: on a device with no browser, Settings' links and an available
 * update say so instead of crashing. Robolectric is set to throw
 * `ActivityNotFoundException` when nothing can take an intent, as such a
 * device does.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNoBrowserTest {
    @get:Rule
    val rule = createComposeRule()

    private val updateCheck = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    private val viewModel =
        mockk<SettingsViewModel>(relaxed = true) {
            every { uiState } returns
                MutableStateFlow(
                    SettingsUiState(
                        accounts = emptyList(),
                        activeAccountId = null,
                        themeMode = ThemeMode.System,
                        textScale = TextScale.Default,
                        syncCadence = SyncCadence.SixHourly,
                        editorPreference = EditorPreference.PreferPlain,
                        pageOpenMode = PageOpenMode.Read,
                        collectives = emptyList(),
                        startScreen = StartScreen.Home,
                        defaultCollectiveId = null,
                    ),
                )
            every { updateCheck } returns this@SettingsNoBrowserTest.updateCheck
            every { syncStatus } returns MutableStateFlow(SyncStatus())
            every { manualSync } returns MutableStateFlow(ManualSyncUiState.Idle)
            every { pendingEditCount } returns MutableStateFlow(null)
        }

    @Before
    fun noBrowser() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        rule.setContent { SettingsScreen(innerPadding = PaddingValues(), onBack = {}, onAddAccount = {}, viewModel = viewModel) }
    }

    @Test
    fun theSourceAndLicenceLinksSaySoInstead() {
        rule.onNodeWithText("Source code").performScrollTo().performClick()
        rule.onNodeWithText("No browser app to open https://github.com/megamaced/nc_collectives_android").assertExists()
    }

    @Test
    fun anAvailableUpdateSaysSoInstead() {
        rule.runOnIdle { updateCheck.value = UpdateCheckUiState.UpdateAvailable(tag = "v9.0.0", htmlUrl = "https://example.com/r") }
        rule.waitForIdle()
        rule.onNodeWithText("A newer version is out, but there's no browser app to open https://example.com/r").assertExists()
    }
}
