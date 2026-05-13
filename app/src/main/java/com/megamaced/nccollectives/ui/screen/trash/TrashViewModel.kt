package com.megamaced.nccollectives.ui.screen.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val isLoading: Boolean = false,
    val items: List<Page> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: PageRepository,
    ) : ViewModel() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.Trash.ARG_COLLECTIVE_ID),
        )

        private val _uiState = MutableStateFlow(TrashUiState())
        val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isLoading) return
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            viewModelScope.launch {
                val result = repository.listTrashedPages(collectiveId)
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

        fun restore(pageId: Long) {
            viewModelScope.launch {
                val result = repository.restorePage(collectiveId, pageId)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(
                                statusMessage = "Page restored",
                                items = it.items.filter { p -> p.id != pageId },
                            )
                        else -> it.copy(statusMessage = result.userMessage())
                    }
                }
            }
        }

        fun purge(pageId: Long) {
            viewModelScope.launch {
                val result = repository.purgePage(collectiveId, pageId)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(
                                statusMessage = "Page permanently deleted",
                                items = it.items.filter { p -> p.id != pageId },
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
