package com.megamaced.nccollectives.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.LogoutHandler
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val userPreferences: UserPreferences,
        private val tokenStore: TokenStore,
        private val logoutHandler: LogoutHandler,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> = userPreferences.flow
            .map { toState(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = toState(UserPrefs()),
            )

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { userPreferences.setThemeMode(mode) }
        }

        fun setSyncCadence(cadence: SyncCadence) {
            viewModelScope.launch { userPreferences.setSyncCadence(cadence) }
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
            )
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
