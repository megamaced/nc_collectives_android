package com.megamaced.nccollectives.ui.screen.share

import android.content.Context
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
import com.megamaced.nccollectives.ui.attachment.uriDisplayName
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
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

/**
 * A capture that created its page but didn't finish writing the markdown
 * references to the images it staged (issue #25).
 *
 * Creating a page from a share is several commits with no shared operation
 * identity — create the page, save its body, stage the images, append the
 * refs — so a failure part-way leaves work behind that a retry has to
 * *resume*. Without this the retry called `createPage` again and made a
 * second page on the server, with the first still there holding the text.
 */
data class PartialCreate(
    val pageId: Long,
    val pageTitle: String,
    val imageRefs: String,
)

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
    /** Set when a create got as far as the page but not its image refs. */
    val partialCreate: PartialCreate? = null,
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
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                emptyList(),
            )

        private val _pagesForCollective = MutableStateFlow<List<Page>>(emptyList())
        val pagesForCollective: StateFlow<List<Page>> = _pagesForCollective.asStateFlow()

        private val _uiState = MutableStateFlow(ShareCaptureUiState())
        val uiState: StateFlow<ShareCaptureUiState> = _uiState.asStateFlow()

        init {
            // B-77: collected, not read once. The old `payload.value` snapshot
            // in `init` meant a second share arriving while this screen was
            // alive changed nothing here — the navigation effect in
            // `NcCollectivesScaffold` keys on whether a payload *exists*, not
            // on which one, so no re-navigation happened either and the user
            // ended up saving the first share's content under the second
            // share's expectations.
            //
            // Nulls are ignored: `consume()` clears the holder as part of a
            // successful save, and blanking the payload out from under the
            // screen mid-finish would only strand it.
            viewModelScope.launch {
                sharePayloadHolder.payload.collect { payload ->
                    if (payload == null) return@collect
                    _uiState.update { state ->
                        if (state.payload == payload) {
                            state
                        } else {
                            // New content: the derived title goes back to the
                            // new payload's default rather than keeping a title
                            // the user typed for something else, and any
                            // half-finished create belongs to the payload
                            // that is being replaced (issue #25) — resuming
                            // it would append this share's images to the
                            // previous share's page.
                            state.copy(
                                payload = payload,
                                title = defaultTitle(payload),
                                errorMessage = null,
                                partialCreate = null,
                            )
                        }
                    }
                }
            }
        }

        private var pagesJob: Job? = null

        fun selectCollective(id: Long) {
            // B-55: dedupe by id. The auto-pick `LaunchedEffect(collectives)`
            // in the share screen keys on the list reference, so any
            // re-emission of the collectives flow (background sync, Room
            // notifying observers) re-fires `selectCollective(id)` for the
            // already-selected collective and triggers a redundant
            // refresh + observer churn.
            if (_uiState.value.selectedCollectiveId == id) return
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
                        resume = state.partialCreate,
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
            resume: PartialCreate?,
        ) {
            // Issue #25: the page and the staged images already exist from
            // the attempt that failed; all that is left is the append. Going
            // back through `createPage` would make a second page and
            // `queueImages` would stage a second copy of every image.
            if (resume != null) {
                finishCreate(payload = payload, created = resume)
                return
            }
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
            val resolvedNames = queueImages(newPage.id, payload)
            // If any image actually staged, append their markdown refs using
            // the resolved (collision-resolved, sanitised) filenames returned
            // by enqueueUpload — B-30/R-39 + S-13.
            //
            // Issue #25: the outcome is read now. It used to be discarded,
            // so a page whose image references were never saved reported a
            // clean capture — the images staged and uploading, and nothing in
            // the body pointing at them.
            finishCreate(
                payload = payload,
                created = PartialCreate(
                    pageId = newPage.id,
                    pageTitle = newPage.title,
                    imageRefs = if (resolvedNames.isEmpty()) "" else imageRefMarkdown(resolvedNames),
                ),
            )
        }

        /**
         * The last commit of a create: put the staged images' markdown
         * references into the page body, and report.
         *
         * Split out so a retry can re-enter here. Issue #25: the outcome is
         * read now — it used to be discarded, so a page whose image
         * references were never saved reported a clean capture, with the
         * images uploading and nothing in the body pointing at them.
         */
        private suspend fun finishCreate(
            payload: SharePayload,
            created: PartialCreate,
        ) {
            val refsOutcome = if (created.imageRefs.isEmpty()) {
                SaveOutcome.Saved
            } else {
                pageRepository.appendToPage(created.pageId, created.imageRefs)
            }
            if (refsOutcome is SaveOutcome.Error) {
                // The page and its text are saved and the images are queued;
                // only the references failed. Say so rather than reporting
                // success, keep the payload, and remember what has already
                // happened so a retry finishes this page instead of making
                // another one.
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = savedMessage(created.pageTitle, refsOutcome),
                        partialCreate = created,
                    )
                }
                return
            }
            sharePayloadHolder.consume(payload.id)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = true,
                    finishedMessage = savedMessage(created.pageTitle, refsOutcome),
                    partialCreate = null,
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
            val resolvedNames = queueImages(pageId, payload)
            val appendBody = buildString {
                payload.text?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (resolvedNames.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(imageRefMarkdown(resolvedNames))
                }
            }
            val outcome = pageRepository.appendToPage(pageId, appendBody)
            val message = when (outcome) {
                SaveOutcome.Saved -> "Appended"
                SaveOutcome.Queued -> "Appended (queued, will sync when online)"
                SaveOutcome.Conflict -> "Page changed on the server; appended as a draft"
                is SaveOutcome.Error -> null
            }
            val error = (outcome as? SaveOutcome.Error)?.message
            if (error == null) sharePayloadHolder.consume(payload.id)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = error == null,
                    finishedMessage = message,
                    errorMessage = error,
                )
            }
        }

        /**
         * Stage every shareable image. Returns the list of resolved (sanitised
         * + collision-free) filenames the worker will upload them under — the
         * markdown image refs must use these names, not the raw display names
         * (B-30/R-39).
         */
        private suspend fun queueImages(
            pageId: Long,
            payload: SharePayload,
        ): List<String> =
            payload.images.mapNotNull { uri ->
                val type = context.contentResolver.getType(uri)
                // S-5: refuse non-image Uris. The manifest only declares
                // image/* + text/plain intent filters, so the OS routes
                // matching senders only — but a malicious app can still
                // target the activity explicitly with any mime.
                if (type != null && !type.startsWith("image/")) {
                    return@mapNotNull null
                }
                val suggestion = uriDisplayName(context, uri) ?: "share-${System.currentTimeMillis()}.jpg"
                attachmentRepository.enqueueUpload(pageId, uri, suggestion, type)
            }

        private fun imageRefMarkdown(resolvedNames: List<String>): String = resolvedNames.joinToString("\n") { name -> "![$name]($name)" }

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
    }

/**
 * What to tell the user after a share created a page.
 *
 * Issue #25: a capture whose image references only reached the edit queue is
 * a success — the offline design working — but it is not the same success as
 * one already on the server, and reporting it as though it were is how a
 * partial result passes for a clean one. The other two arms cannot come from
 * a page created seconds ago on this device, and are spelled out rather than
 * folded into an `else` so a change to `SaveOutcome` has to come back here.
 */
internal fun savedMessage(
    title: String,
    refsOutcome: SaveOutcome,
): String =
    when (refsOutcome) {
        SaveOutcome.Saved -> "Saved as \"$title\""
        SaveOutcome.Queued -> "Saved as \"$title\" (image links will sync when online)"
        SaveOutcome.Conflict -> "Saved as \"$title\"; the image links are waiting as a draft"
        is SaveOutcome.Error -> "Saved as \"$title\", but the image links didn't: ${refsOutcome.message}"
    }
