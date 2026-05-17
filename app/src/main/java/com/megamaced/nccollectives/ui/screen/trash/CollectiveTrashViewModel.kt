package com.megamaced.nccollectives.ui.screen.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectiveTrashUiState(
    val isLoading: Boolean = false,
    val items: List<Collective> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class CollectiveTrashViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CollectiveTrashUiState())
        val uiState: StateFlow<CollectiveTrashUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isLoading) return
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            viewModelScope.launch {
                val result = repository.listTrashedCollectives()
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(isLoading = false, items = result.data, errorMessage = null)
                        else ->
                            it.copy(isLoading = false, errorMessage = result.userMessage())
                    }
                }
            }
        }

        fun restore(collectiveId: Long) {
            viewModelScope.launch {
                val result = repository.restoreTrashedCollective(collectiveId)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(
                                statusMessage = "Collective restored",
                                items = it.items.filter { c -> c.id != collectiveId },
                            )
                        else -> it.copy(statusMessage = result.userMessage())
                    }
                }
            }
        }

        fun purge(collectiveId: Long) {
            viewModelScope.launch {
                val result = repository.permanentlyDeleteCollective(collectiveId)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(
                                statusMessage = "Collective permanently deleted",
                                items = it.items.filter { c -> c.id != collectiveId },
                            )
                        else -> it.copy(statusMessage = result.userMessage())
                    }
                }
            }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }
    }
