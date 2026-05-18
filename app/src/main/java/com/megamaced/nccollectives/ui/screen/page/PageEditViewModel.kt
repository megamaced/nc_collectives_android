package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
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
        private val attachmentRepository: AttachmentRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageEdit.ARG_PAGE_ID),
        )

        private val _uiState = MutableStateFlow(PageEditUiState())
        val uiState: StateFlow<PageEditUiState> = _uiState.asStateFlow()

        private val _imageBaseUrl = MutableStateFlow<String?>(null)
        val imageBaseUrl: StateFlow<String?> = _imageBaseUrl.asStateFlow()

        init {
            viewModelScope.launch {
                // R-37: keep the spinner up across both getPage AND fetchBody,
                // and stage `initialBody` exactly once at the end. The
                // previous shape staged a null body first (no spinner up
                // yet) and then overwrote it when fetchBody returned — a
                // user typing in that brief gap would see their text vanish
                // when the body arrived.
                _uiState.update { it.copy(isLoadingBody = true) }
                val page = repository.getPage(pageId)
                val needsFetch = page != null && page.bodyMd == null
                val fetched = if (needsFetch) repository.fetchBody(pageId) else null
                _imageBaseUrl.value = attachmentRepository.attachmentsBaseUrl(pageId)
                _uiState.update {
                    it.copy(
                        title = page?.title.orEmpty(),
                        initialBody = when {
                            page == null -> null
                            fetched is ApiResult.Success -> fetched.data
                            else -> page.bodyMd
                        },
                        isLoadingBody = false,
                        saveError = fetched
                            ?.takeIf { it !is ApiResult.Success<*> }
                            ?.userMessage(),
                    )
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
