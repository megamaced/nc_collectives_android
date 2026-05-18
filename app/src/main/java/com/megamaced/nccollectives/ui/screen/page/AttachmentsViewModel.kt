package com.megamaced.nccollectives.ui.screen.page

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AttachmentsUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class AttachmentsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: AttachmentRepository,
    ) : ViewModel() {
        val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.Attachments.ARG_PAGE_ID),
        )

        val attachments: StateFlow<List<Attachment>> = repository.observeForPage(pageId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

        private val _uiState = MutableStateFlow(AttachmentsUiState())
        val uiState: StateFlow<AttachmentsUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isRefreshing) return
            _uiState.update { it.copy(isRefreshing = true) }
            viewModelScope.launch {
                val result = repository.refresh(pageId)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        statusMessage = if (result is ApiResult.Success) it.statusMessage else result.userMessage(),
                    )
                }
            }
        }

        fun enqueueUpload(
            uri: Uri,
            suggestedName: String,
            contentType: String?,
        ) {
            viewModelScope.launch {
                val resolved = repository.enqueueUpload(pageId, uri, suggestedName, contentType)
                _uiState.update {
                    it.copy(
                        statusMessage = if (resolved != null) {
                            "Uploading $resolved…"
                        } else {
                            "Couldn't read $suggestedName"
                        },
                    )
                }
            }
        }

        fun delete(fileName: String) {
            viewModelScope.launch {
                val result = repository.delete(pageId, fileName)
                _uiState.update {
                    it.copy(
                        statusMessage = if (result is ApiResult.Success) "$fileName deleted" else result.userMessage(),
                    )
                }
            }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }
    }
