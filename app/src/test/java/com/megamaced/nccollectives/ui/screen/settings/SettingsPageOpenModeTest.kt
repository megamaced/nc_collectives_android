package com.megamaced.nccollectives.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings offers how pages open, under Editor: as each collective is set, unless the user picks Read mode or Edit mode. */
@RunWith(AndroidJUnit4::class)
class SettingsPageOpenModeTest {
    @get:Rule
    val rule = createComposeRule()

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
                        pageOpenMode = PageOpenMode.AsCollective,
                        collectives = emptyList(),
                        startScreen = StartScreen.Home,
                        defaultCollectiveId = null,
                    ),
                )
            every { updateCheck } returns MutableStateFlow(UpdateCheckUiState.Idle)
            every { syncStatus } returns MutableStateFlow(SyncStatus())
            every { manualSync } returns MutableStateFlow(ManualSyncUiState.Idle)
            every { pendingEditCount } returns MutableStateFlow(null)
        }

    @Test
    fun eachCollectivesOwnModeIsChosenUntilReadModeOrEditModeIsPicked() {
        rule.setContent { SettingsScreen(innerPadding = PaddingValues(), onBack = {}, onAddAccount = {}, viewModel = viewModel) }

        rule.onNodeWithText("As each collective is set (default)").performScrollTo().assertIsSelected()
        rule.onNodeWithText("Read mode").performScrollTo().performClick()
        rule.onNodeWithText("Edit mode").performScrollTo().performClick()

        verify { viewModel.setPageOpenMode(PageOpenMode.Read) }
        verify { viewModel.setPageOpenMode(PageOpenMode.Edit) }
    }
}
