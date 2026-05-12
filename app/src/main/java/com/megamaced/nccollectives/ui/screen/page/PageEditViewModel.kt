package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PageEditUiState(
    val title: String = "",
    val initialBody: String? = null,
    val isLoadingBody: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSucceeded: Boolean = false,
)

@HiltViewModel
class PageEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: PageRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageEdit.ARG_PAGE_ID),
        )

        private val _uiState = MutableStateFlow(PageEditUiState())
        val uiState: StateFlow<PageEditUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val page = repository.getPage(pageId)
                _uiState.update {
                    it.copy(
                        title = page?.title.orEmpty(),
                        initialBody = page?.bodyMd,
                    )
                }
                if (page != null && page.bodyMd == null) {
                    _uiState.update { it.copy(isLoadingBody = true) }
                    val result = repository.fetchBody(pageId)
                    _uiState.update {
                        it.copy(
                            isLoadingBody = false,
                            initialBody = if (result is ApiResult.Success) result.data else it.initialBody,
                            saveError = if (result is ApiResult.Success) null else result.userMessage(),
                        )
                    }
                }
            }
        }

        fun save(body: String) {
            if (_uiState.value.isSaving) return
            _uiState.update { it.copy(isSaving = true, saveError = null) }
            viewModelScope.launch {
                val outcome = repository.saveBody(pageId, body)
                _uiState.update {
                    when (outcome) {
                        SaveOutcome.Saved, SaveOutcome.Queued ->
                            it.copy(isSaving = false, saveSucceeded = true)
                        SaveOutcome.Conflict ->
                            it.copy(
                                isSaving = false,
                                saveError = "Page changed on the server. Your edits were saved as a draft you can resolve on the page.",
                                saveSucceeded = true,
                            )
                        is SaveOutcome.Error ->
                            it.copy(isSaving = false, saveError = outcome.message)
                    }
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(saveError = null) }
        }
    }
