package com.megamaced.nccollectives.ui.screen.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ShareMode { NEW_PAGE, APPEND }

data class ShareCaptureUiState(
    val payload: SharePayload? = null,
    val mode: ShareMode = ShareMode.NEW_PAGE,
    val title: String = "",
    val selectedCollectiveId: Long? = null,
    val selectedParentPageId: Long? = null,
    val selectedAppendPageId: Long? = null,
    val isSaving: Boolean = false,
    val finished: Boolean = false,
    val finishedMessage: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class ShareCaptureViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sharePayloadHolder: SharePayloadHolder,
        private val pageRepository: PageRepository,
        private val collectiveRepository: CollectiveRepository,
        private val attachmentRepository: AttachmentRepository,
    ) : ViewModel() {
        val collectives: StateFlow<List<Collective>> =
            collectiveRepository.observeCollectives().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        private val _pagesForCollective = MutableStateFlow<List<Page>>(emptyList())
        val pagesForCollective: StateFlow<List<Page>> = _pagesForCollective.asStateFlow()

        private val _uiState = MutableStateFlow(ShareCaptureUiState())
        val uiState: StateFlow<ShareCaptureUiState> = _uiState.asStateFlow()

        init {
            val payload = sharePayloadHolder.payload.value
            _uiState.update {
                it.copy(
                    payload = payload,
                    title = defaultTitle(payload),
                )
            }
        }

        private var pagesJob: Job? = null

        fun selectCollective(id: Long) {
            _uiState.update {
                it.copy(
                    selectedCollectiveId = id,
                    selectedParentPageId = null,
                    selectedAppendPageId = null,
                )
            }
            pagesJob?.cancel()
            pagesJob = viewModelScope.launch {
                pageRepository.refresh(id)
                pageRepository.observePages(id).collect { list ->
                    _pagesForCollective.value = list
                    val landing = list.firstOrNull { it.parentId == 0L }
                    if (_uiState.value.selectedParentPageId == null && landing != null) {
                        _uiState.update { state -> state.copy(selectedParentPageId = landing.id) }
                    }
                }
            }
        }

        fun setMode(mode: ShareMode) {
            _uiState.update { it.copy(mode = mode) }
        }

        fun setTitle(value: String) {
            _uiState.update { it.copy(title = value) }
        }

        fun selectParent(pageId: Long) {
            _uiState.update { it.copy(selectedParentPageId = pageId) }
        }

        fun selectAppendTarget(pageId: Long) {
            _uiState.update { it.copy(selectedAppendPageId = pageId) }
        }

        fun submit() {
            val state = _uiState.value
            val payload = state.payload ?: return
            val collectiveId = state.selectedCollectiveId ?: return
            if (state.isSaving) return
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            viewModelScope.launch {
                when (state.mode) {
                    ShareMode.NEW_PAGE -> handleCreate(
                        collectiveId = collectiveId,
                        parentId = state.selectedParentPageId,
                        payload = payload,
                        title = state.title,
                    )
                    ShareMode.APPEND -> handleAppend(
                        pageId = state.selectedAppendPageId,
                        payload = payload,
                    )
                }
            }
        }

        private suspend fun handleCreate(
            collectiveId: Long,
            parentId: Long?,
            payload: SharePayload,
            title: String,
        ) {
            if (parentId == null) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Pick a parent page first") }
                return
            }
            val finalTitle = title.trim().ifEmpty { defaultTitle(payload) }
            val initialBody = buildInitialBody(payload)
            val result = pageRepository.createPage(collectiveId, parentId, finalTitle, initialBody)
            if (result !is ApiResult.Success) {
                _uiState.update { it.copy(isSaving = false, errorMessage = result.userMessage()) }
                return
            }
            val newPage = result.data
            queueImages(newPage.id, payload)
            // If we queued images, also append their markdown refs to the
            // body so the page renders them on next render.
            if (payload.images.isNotEmpty()) {
                val imageRefs = imageRefMarkdown(payload)
                pageRepository.appendToPage(newPage.id, imageRefs)
            }
            sharePayloadHolder.consume()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = true,
                    finishedMessage = "Saved as \"${newPage.title}\"",
                )
            }
        }

        private suspend fun handleAppend(
            pageId: Long?,
            payload: SharePayload,
        ) {
            if (pageId == null) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Pick a page to append to") }
                return
            }
            val appendBody = buildString {
                payload.text?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (payload.images.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(imageRefMarkdown(payload))
                }
            }
            queueImages(pageId, payload)
            val outcome = pageRepository.appendToPage(pageId, appendBody)
            val message = when (outcome) {
                SaveOutcome.Saved -> "Appended"
                SaveOutcome.Queued -> "Appended (queued, will sync when online)"
                SaveOutcome.Conflict -> "Page changed on the server; appended as a draft"
                is SaveOutcome.Error -> null
            }
            val error = (outcome as? SaveOutcome.Error)?.message
            if (error == null) sharePayloadHolder.consume()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = error == null,
                    finishedMessage = message,
                    errorMessage = error,
                )
            }
        }

        private suspend fun queueImages(
            pageId: Long,
            payload: SharePayload,
        ) {
            payload.images.forEach { uri ->
                val name = displayNameFor(uri) ?: "share-${System.currentTimeMillis()}.jpg"
                val type = context.contentResolver.getType(uri)
                attachmentRepository.enqueueUpload(pageId, uri, name, type)
            }
        }

        private fun imageRefMarkdown(payload: SharePayload): String =
            payload.images
                .joinToString("\n") { uri ->
                    val display = displayNameFor(uri) ?: "image.jpg"
                    "![$display]($display)"
                }

        private fun buildInitialBody(payload: SharePayload): String =
            buildString {
                payload.text?.takeIf { it.isNotBlank() }?.let { append(it) }
                // Images are appended after the page is created so the
                // collision-resolved filenames from `enqueueUpload` make it
                // into the markdown.
            }

        private fun defaultTitle(payload: SharePayload?): String {
            if (payload == null) return "Shared note"
            payload.subject?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
            payload.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                return it.take(60).trim()
            }
            return "Shared note"
        }

        private fun displayNameFor(uri: Uri): String? {
            val resolver = context.contentResolver
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            return uri.lastPathSegment
        }
    }
