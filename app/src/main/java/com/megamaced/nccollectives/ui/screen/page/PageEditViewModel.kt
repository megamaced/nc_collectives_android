package com.megamaced.nccollectives.ui.screen.page

import android.net.Uri
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
    /**
     * Message that has to be seen *before* the editor closes — currently
     * only the conflict-became-a-draft case.
     *
     * B-79: this used to travel in [saveError] alongside `saveSucceeded =
     * true`, and the `saveSucceeded` effect (declared first) called
     * `onClose()` before the snackbar effect ever ran, so the one message
     * the user actually needed was the one message they never saw. The
     * screen now holds the close until this has been shown.
     */
    val saveNotice: String? = null,
)

@HiltViewModel
class PageEditViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
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

        /**
         * The editor's live text — the authoritative copy of what the user
         * has typed.
         *
         * B-71: it used to be a `remember { mutableStateOf(TextFieldValue()) }`
         * inside `PageEditScreen`. `MainActivity` declares no
         * `android:configChanges`, so a rotation — or a dark-mode toggle, a
         * font-size change, a multi-window resize — destroyed the activity and
         * reset the field to "", while this ViewModel (scoped to the nav
         * entry) survived holding the original `initialBody`; the seeding pass
         * then re-applied the *pre-edit* body over the top. The user's typing
         * was gone silently, and because the field matched `initialBody`
         * again, `hasUnsavedChanges` reported nothing to discard.
         *
         * `SavedStateHandle` rather than a plain `MutableStateFlow` so the
         * draft also survives process death, which is routine while the
         * camera app is in front.
         */
        val draftBody: StateFlow<String> = savedStateHandle.getStateFlow(KEY_DRAFT, "")

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
                // B-58: revalidate rather than fetching only when the body is
                // missing. Editing a stale body is worse than viewing one —
                // the save carries the stale etag, the server rejects it on
                // `If-Match`, and the user's work lands in a conflict draft
                // they then have to resolve by hand. Cheap: an unchanged page
                // is a 304.
                val refreshed = if (page == null) null else repository.refreshBodyIfChanged(pageId)
                val current = if (refreshed is ApiResult.Success && refreshed.data) {
                    // Body moved on — re-read the row rather than editing the
                    // copy we loaded a moment ago.
                    repository.getPage(pageId)
                } else {
                    page
                }
                _imageBaseUrl.value = attachmentRepository.attachmentsBaseUrl(pageId)
                seedDraft(current?.bodyMd)
                _uiState.update {
                    it.copy(
                        title = current?.title.orEmpty(),
                        initialBody = current?.bodyMd,
                        isLoadingBody = false,
                        // Only complain when there's nothing to edit. A failed
                        // revalidation over a cached body is the offline case,
                        // and the editor still works there — the save path
                        // queues the edit for `EditFlushWorker`.
                        saveError = refreshed
                            ?.takeIf { it !is ApiResult.Success<*> && current?.bodyMd == null }
                            ?.userMessage(),
                    )
                }
            }
        }

        /**
         * Fill the editor with the loaded body, once.
         *
         * The old guard was `fieldValue.text.isEmpty()`, which isn't a
         * seeded/not-seeded test at all: a user who selected everything and
         * deleted it looks exactly like a screen that has never been seeded,
         * and got the pre-edit body poured back in. Only an explicit flag can
         * tell the two apart, and it is persisted next to the draft so it
         * survives the same restarts the draft does.
         *
         * Nothing is seeded until there is a body to seed with — a failed
         * fetch with no cached copy leaves the editor untouched, as before.
         */
        private fun seedDraft(body: String?) {
            if (body == null) return
            if (savedStateHandle.get<Boolean>(KEY_SEEDED) == true) return
            savedStateHandle[KEY_DRAFT] = body
            savedStateHandle[KEY_SEEDED] = true
        }

        /**
         * Stage an attachment captured or picked from the editor. The transfer
         * itself is `AttachmentUploadWorker`'s job; the row appears in the
         * picker sheet as PENDING straight away.
         *
         * B-73: this lives here rather than being reached through
         * `AttachmentsViewModel` so that `PageEditScreen` can register its
         * activity-result launchers unconditionally without constructing that
         * ViewModel — which refreshes the attachment list over the network in
         * its `init` — on every editor open. The sheet still builds it, lazily,
         * for the listing.
         */
        fun enqueueAttachment(
            uri: Uri,
            suggestedName: String,
            contentType: String?,
        ) {
            viewModelScope.launch {
                attachmentRepository.enqueueUpload(pageId, uri, suggestedName, contentType)
            }
        }

        /** The user edited the buffer (typing, or a toolbar action). */
        fun onBodyChanged(body: String) {
            // Typing is itself proof the editor is live, so latch the flag: if
            // the body fetch is still in flight it must not overwrite what the
            // user has already put in.
            savedStateHandle[KEY_SEEDED] = true
            savedStateHandle[KEY_DRAFT] = body
        }

        fun save() {
            if (_uiState.value.isSaving) return
            _uiState.update { it.copy(isSaving = true, saveError = null, saveNotice = null) }
            viewModelScope.launch {
                // The draft is the single source of truth — the screen no
                // longer passes a copy of the text in, so there is nothing to
                // drift out of sync with a toolbar action that forgot to
                // report its result.
                val outcome = repository.saveBody(pageId, draftBody.value)
                _uiState.update {
                    when (outcome) {
                        SaveOutcome.Saved, SaveOutcome.Queued -> {
                            it.copy(isSaving = false, saveSucceeded = true)
                        }

                        SaveOutcome.Conflict -> {
                            it.copy(
                                isSaving = false,
                                saveNotice = "Page changed on the server. Your edits were saved as a draft you can resolve on the page.",
                                saveSucceeded = true,
                            )
                        }

                        is SaveOutcome.Error -> {
                            it.copy(isSaving = false, saveError = outcome.message)
                        }
                    }
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(saveError = null) }
        }

        private companion object {
            /** Editor text, persisted across configuration change + process death. */
            const val KEY_DRAFT = "pageEdit.draftBody"

            /** Whether [KEY_DRAFT] has been filled from the loaded body yet. */
            const val KEY_SEEDED = "pageEdit.seeded"
        }
    }
