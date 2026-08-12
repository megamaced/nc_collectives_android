package com.megamaced.nccollectives.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.LogoutHandler
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.SyncStatus
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.sync.FullSync
import com.megamaced.nccollectives.sync.SyncOutcome
import com.megamaced.nccollectives.util.ManualCheckResult
import com.megamaced.nccollectives.util.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    /** Cached, non-trashed collectives offered as startup destinations. */
    val collectives: List<Collective>,
    /** Collective opened on launch, or null for the collective list. */
    val defaultCollectiveId: Long?,
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

/**
 * State for the manual "Sync now" affordance. Same shape and rationale as
 * [UpdateCheckUiState]: a separate flow so the row can show a spinner and
 * report an outcome without entangling it with the observation-driven
 * preferences state.
 */
sealed interface ManualSyncUiState {
    data object Idle : ManualSyncUiState

    data object Syncing : ManualSyncUiState

    data object Done : ManualSyncUiState

    data class Failed(
        val message: String,
    ) : ManualSyncUiState
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val userPreferences: UserPreferences,
        private val tokenStore: TokenStore,
        private val logoutHandler: LogoutHandler,
        private val updateChecker: UpdateChecker,
        private val fullSync: FullSync,
        collectiveRepository: CollectiveRepository,
    ) : ViewModel() {
        /**
         * Last-sync state, observed rather than snapshotted so a background
         * `SyncWorker` run updates the line while Settings is open.
         */
        val syncStatus: StateFlow<SyncStatus> = userPreferences.syncStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SyncStatus(),
        )

        val uiState: StateFlow<SettingsUiState> = combine(
            userPreferences.flow,
            collectiveRepository.observeCollectives(),
        ) { prefs, collectives -> toState(prefs, collectives) }
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
                    collectives = emptyList(),
                    defaultCollectiveId = null,
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

        /** Pass null to go back to landing on the collective list. */
        fun setDefaultCollective(collectiveId: Long?) {
            viewModelScope.launch { userPreferences.setDefaultCollectiveId(collectiveId) }
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

        private val _manualSync = MutableStateFlow<ManualSyncUiState>(ManualSyncUiState.Idle)
        val manualSync: StateFlow<ManualSyncUiState> = _manualSync.asStateFlow()

        /**
         * Explicit "Sync now". Runs [FullSync] inline rather than enqueuing a
         * `SyncWorker` so the user gets a result they can see: WorkManager
         * would return immediately and leave them watching an unchanged
         * screen, which is the complaint this whole batch exists to answer.
         *
         * A [SyncOutcome.Retryable] is reported as a plain failure here —
         * there's no background retry attached to a button press, and telling
         * someone "we'll try again later" when they explicitly asked now is
         * worse than telling them it didn't work.
         */
        fun syncNow() {
            if (_manualSync.value is ManualSyncUiState.Syncing) return
            _manualSync.value = ManualSyncUiState.Syncing
            viewModelScope.launch {
                _manualSync.value = when (val outcome = fullSync.run()) {
                    SyncOutcome.Success -> ManualSyncUiState.Done
                    SyncOutcome.Unauthorised -> ManualSyncUiState.Failed("Session expired — please log in again.")
                    is SyncOutcome.Retryable -> ManualSyncUiState.Failed(outcome.message)
                    is SyncOutcome.Failed -> ManualSyncUiState.Failed(outcome.message)
                }
            }
        }

        /** Mirrors [dismissUpdateCheck] — clears a consumed terminal state. */
        fun dismissManualSync() {
            _manualSync.update { current ->
                if (current is ManualSyncUiState.Syncing) current else ManualSyncUiState.Idle
            }
        }

        fun signOut() {
            logoutHandler.signOut()
        }

        private fun toState(
            prefs: UserPrefs,
            collectives: List<Collective>,
        ): SettingsUiState {
            val credentials = tokenStore.getCredentials()
            return SettingsUiState(
                account = credentials?.let {
                    AccountInfo(host = it.host, loginName = it.loginName)
                },
                themeMode = prefs.themeMode,
                syncCadence = prefs.syncCadence,
                editorPreference = prefs.editorPreference,
                collectives = collectives,
                // Don't surface a selection the list can't show — a default
                // whose collective has been trashed reads as "Collective
                // list" here, matching what the launch path will actually do
                // once `resolveStartupCollective` clears the stale id.
                defaultCollectiveId = prefs.defaultCollectiveId
                    ?.takeIf { id -> collectives.any { it.id == id } },
            )
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
