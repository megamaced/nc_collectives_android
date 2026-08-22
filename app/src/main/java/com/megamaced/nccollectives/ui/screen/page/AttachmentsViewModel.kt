package com.megamaced.nccollectives.ui.screen.page

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.model.OpenableAttachment
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.util.attachmentDirName
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
    /** Filename currently being downloaded for viewing. */
    val downloadingAttachment: String? = null,
    /**
     * Attachment staged and ready to hand off. The screen fires the
     * `ACTION_VIEW` intent and clears this via [AttachmentsViewModel.acknowledgeOpened].
     */
    val attachmentToOpen: OpenableAttachment? = null,
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

        /**
         * Download [fileName] and hand it to another app. Works for images
         * too — opening a photo in a real gallery app gives the user pinch-
         * zoom and share, which a grid thumbnail can't.
         */
        fun open(fileName: String) {
            if (_uiState.value.downloadingAttachment != null) return
            _uiState.update { it.copy(downloadingAttachment = fileName) }
            viewModelScope.launch {
                val relativePath = "${attachmentDirName(pageId)}/$fileName"
                val result = repository.downloadForViewing(pageId, relativePath)
                _uiState.update { state ->
                    when (result) {
                        is ApiResult.Success -> {
                            state.copy(
                                downloadingAttachment = null,
                                attachmentToOpen = result.data,
                            )
                        }

                        else -> {
                            state.copy(
                                downloadingAttachment = null,
                                statusMessage = result.userMessage() ?: "Couldn't open $fileName",
                            )
                        }
                    }
                }
            }
        }

        /** Called once the screen has fired the view intent. */
        fun acknowledgeOpened(failureMessage: String? = null) {
            _uiState.update {
                it.copy(
                    attachmentToOpen = null,
                    statusMessage = failureMessage ?: it.statusMessage,
                )
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
