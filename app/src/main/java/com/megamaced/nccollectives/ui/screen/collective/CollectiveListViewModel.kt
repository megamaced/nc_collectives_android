package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectiveListUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val isCreating: Boolean = false,
    /**
     * Set to the new collective id after a successful create so the UI
     * can navigate straight into the page tree. Cleared by the caller
     * via [acknowledgeCreated].
     */
    val createdCollectiveId: Long? = null,
)

@HiltViewModel
class CollectiveListViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
    ) : ViewModel() {
        val collectives: StateFlow<List<Collective>> =
            repository.observeCollectives().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        private val _uiState = MutableStateFlow(CollectiveListUiState())
        val uiState: StateFlow<CollectiveListUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isRefreshing) return
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                val result = repository.refresh()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(errorMessage = null) }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }

        fun acknowledgeCreated() {
            _uiState.update { it.copy(createdCollectiveId = null) }
        }

        fun createCollective(
            name: String,
            emoji: String?,
        ) {
            if (_uiState.value.isCreating) return
            _uiState.update { it.copy(isCreating = true) }
            viewModelScope.launch {
                val result = repository.createCollective(name, emoji)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(
                                isCreating = false,
                                createdCollectiveId = result.data.id,
                                statusMessage = "Collective created",
                            )
                        else ->
                            it.copy(
                                isCreating = false,
                                statusMessage = result.userMessage(),
                            )
                    }
                }
            }
        }

        fun setEmoji(
            collectiveId: Long,
            emoji: String,
        ) {
            viewModelScope.launch {
                val result = repository.setCollectiveEmoji(collectiveId, emoji)
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun trash(collectiveId: Long) {
            viewModelScope.launch {
                val result = repository.trashCollective(collectiveId)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(statusMessage = "Moved to trash")
                        else ->
                            it.copy(statusMessage = result.userMessage())
                    }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
