package com.megamaced.nccollectives.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.LogoutHandler
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.util.ManualCheckResult
import com.megamaced.nccollectives.util.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountInfo(
    val host: String,
    val loginName: String,
)

data class SettingsUiState(
    val account: AccountInfo?,
    val themeMode: ThemeMode,
    val syncCadence: SyncCadence,
    val editorPreference: EditorPreference,
)

/**
 * State for the manual "Check for updates" affordance. The screen
 * collects this as a separate StateFlow so the button can show a
 * spinner while we're talking to GitHub and surface a snackbar/dialog
 * when the call lands — without entangling the result with the main
 * preferences state, which is observation-driven.
 */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState

    data object Checking : UpdateCheckUiState

    data object UpToDate : UpdateCheckUiState

    data class UpdateAvailable(
        val tag: String,
        val htmlUrl: String,
    ) : UpdateCheckUiState

    data class Failed(
        val message: String,
    ) : UpdateCheckUiState
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val userPreferences: UserPreferences,
        private val tokenStore: TokenStore,
        private val logoutHandler: LogoutHandler,
        private val updateChecker: UpdateChecker,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> = userPreferences.flow
            .map { toState(it) }
            // `toState` reads `EncryptedSharedPreferences` on disk via
            // `tokenStore.getCredentials()`. Force it onto Dispatchers.IO
            // so the disk hit doesn't run on the Compose collector's
            // dispatcher (Main) — B-21. The `initialValue` below uses
            // `account = null` for the same reason: avoids a synchronous
            // disk read at VM construction.
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SettingsUiState(
                    account = null,
                    themeMode = ThemeMode.System,
                    syncCadence = SyncCadence.SixHourly,
                    editorPreference = EditorPreference.PreferPlain,
                ),
            )

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { userPreferences.setThemeMode(mode) }
        }

        fun setSyncCadence(cadence: SyncCadence) {
            viewModelScope.launch { userPreferences.setSyncCadence(cadence) }
        }

        fun setEditorPreference(preference: EditorPreference) {
            viewModelScope.launch { userPreferences.setEditorPreference(preference) }
        }

        private val _updateCheck = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
        val updateCheck: StateFlow<UpdateCheckUiState> = _updateCheck.asStateFlow()

        /**
         * Manual "Check for updates" — bypasses the 24h startup throttle and
         * pushes the result through [updateCheck]. No-ops if a check is
         * already in flight so a double-tap doesn't fire two GitHub requests.
         */
        fun checkForUpdate() {
            if (_updateCheck.value is UpdateCheckUiState.Checking) return
            _updateCheck.value = UpdateCheckUiState.Checking
            viewModelScope.launch {
                _updateCheck.value = when (val result = updateChecker.checkNow()) {
                    ManualCheckResult.UpToDate -> UpdateCheckUiState.UpToDate
                    is ManualCheckResult.UpdateAvailable ->
                        UpdateCheckUiState.UpdateAvailable(tag = result.tag, htmlUrl = result.htmlUrl)
                    is ManualCheckResult.Failed -> UpdateCheckUiState.Failed(result.message)
                }
            }
        }

        /**
         * Called after the screen has consumed a terminal update-check state
         * (snackbar dismissed, browser launched) so a subsequent tap re-fires
         * the check cleanly.
         */
        fun dismissUpdateCheck() {
            _updateCheck.update { current ->
                if (current is UpdateCheckUiState.Checking) current else UpdateCheckUiState.Idle
            }
        }

        fun signOut() {
            logoutHandler.signOut()
        }

        private fun toState(prefs: UserPrefs): SettingsUiState {
            val credentials = tokenStore.getCredentials()
            return SettingsUiState(
                account = credentials?.let {
                    AccountInfo(host = it.host, loginName = it.loginName)
                },
                themeMode = prefs.themeMode,
                syncCadence = prefs.syncCadence,
                editorPreference = prefs.editorPreference,
            )
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
