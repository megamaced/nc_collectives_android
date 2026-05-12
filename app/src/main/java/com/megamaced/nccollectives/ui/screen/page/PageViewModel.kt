package com.megamaced.nccollectives.ui.screen.page

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

data class PageViewUiState(
    val page: Page? = null,
    val body: String? = null,
    val isLoadingBody: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class PageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: PageRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageView.ARG_PAGE_ID),
        )

        private val _uiState = MutableStateFlow(PageViewUiState())
        val uiState: StateFlow<PageViewUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val page = repository.getPage(pageId)
                _uiState.update { it.copy(page = page, body = page?.bodyMd) }
                if (page != null && page.bodyMd == null) refreshBody()
            }
        }

        fun refreshBody() {
            if (_uiState.value.isLoadingBody) return
            _uiState.update { it.copy(isLoadingBody = true, errorMessage = null) }
            viewModelScope.launch {
                val result = repository.fetchBody(pageId)
                _uiState.update { state ->
                    state.copy(
                        isLoadingBody = false,
                        body = if (result is ApiResult.Success) result.data else state.body,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }
