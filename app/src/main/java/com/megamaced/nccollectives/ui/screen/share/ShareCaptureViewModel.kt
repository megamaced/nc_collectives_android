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
import timber.log.Timber
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
    /** What `createPage` managed to do with the shared text (issue #31). */
    val bodyOutcome: SaveOutcome,
    /** Shared images that couldn't be staged at all (issue #31). */
    val imagesDropped: Int,
    /** Whether the share carried any text, so "nothing saved" can be told apart. */
    val hasText: Boolean,
)

/** What [ShareCaptureViewModel.queueImages] managed to stage, and what it didn't. */
internal data class StagedImages(
    val names: List<String>,
    val dropped: Int,
)

/**
 * What to tell the user, and whether the share is finished with.
 *
 * [resumable] is the narrow case worth distinguishing: the page exists and
 * trying again would finish it. A dropped image is *not* resumable — the URI
 * will not become readable — so it is reported and the payload consumed
 * anyway, which is the "UI explicitly reports partial completion" half of
 * issue #31.
 */
internal data class CaptureReport(
    val message: String,
    val succeeded: Boolean,
    val resumable: Boolean,
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
            val created = result.data
            val newPage = created.page
            val staged = queueImages(newPage.id, payload)
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
                    imageRefs = if (staged.names.isEmpty()) "" else imageRefMarkdown(staged.names),
                    bodyOutcome = created.bodyOutcome,
                    imagesDropped = staged.dropped,
                    hasText = !payload.text.isNullOrBlank(),
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
            val report = captureReport(created, refsOutcome)
            if (report.resumable) {
                // Something the user can act on by trying again: the page and
                // its text are saved and the images are queued, but the
                // references didn't land. Keep the payload, and remember what
                // has already happened so a retry finishes this page instead
                // of making another one.
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = report.message, partialCreate = created)
                }
                return
            }
            // Consumed even when [report] carries a loss, which is the point
            // of reporting it: a URI that couldn't be staged will not stage
            // on a second attempt either, so holding the payload would only
            // offer a retry that cannot help (issue #31).
            sharePayloadHolder.consume(payload.id)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = report.succeeded,
                    finishedMessage = report.message.takeIf { _ -> report.succeeded },
                    errorMessage = report.message.takeUnless { _ -> report.succeeded },
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
            val staged = queueImages(pageId, payload)
            val appendBody = buildString {
                payload.text?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (staged.names.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(imageRefMarkdown(staged.names))
                }
            }
            if (appendBody.isEmpty()) {
                // Issue #31: everything the share carried was dropped —
                // typically an image-only share whose URIs couldn't be read.
                // Appending an empty string and reporting "Appended" is what
                // this used to do.
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = nothingToSaveMessage(staged.dropped))
                }
                return
            }
            val outcome = pageRepository.appendToPage(pageId, appendBody)
            val report = appendReport(outcome, staged.dropped)
            if (report.succeeded) sharePayloadHolder.consume(payload.id)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    finished = report.succeeded,
                    finishedMessage = report.message.takeIf { _ -> report.succeeded },
                    errorMessage = report.message.takeUnless { _ -> report.succeeded },
                )
            }
        }

        /**
         * Stage every shareable image, and say how many could not be.
         *
         * The names returned are the resolved (sanitised + collision-free)
         * filenames the worker will upload them under — the markdown image
         * refs must use these, not the raw display names (B-30/R-39).
         *
         * Issue #31: this used to be a `mapNotNull` returning only the
         * survivors, so a URI that couldn't be read and a URI that was never
         * an image both vanished without trace. Both create and append then
         * built markdown from the shorter list, consumed the payload and
         * reported success — an image-only share whose URIs all failed made a
         * blank page and called it saved.
         */
        private suspend fun queueImages(
            pageId: Long,
            payload: SharePayload,
        ): StagedImages {
            val names = mutableListOf<String>()
            var dropped = 0
            for (uri in payload.images) {
                val type = context.contentResolver.getType(uri)
                // S-5: refuse non-image Uris. The manifest only declares
                // image/* + text/plain intent filters, so the OS routes
                // matching senders only — but a malicious app can still
                // target the activity explicitly with any mime.
                if (type != null && !type.startsWith("image/")) {
                    Timber.w("Refusing shared %s: not an image", type)
                    dropped++
                    continue
                }
                val suggestion = uriDisplayName(context, uri) ?: "share-${System.currentTimeMillis()}.jpg"
                val resolved = attachmentRepository.enqueueUpload(pageId, uri, suggestion, type)
                if (resolved == null) {
                    // The bytes couldn't be read or copied. Nothing a retry
                    // from this screen can fix — the grant may be gone, or
                    // the source evicted — so the user has to be told rather
                    // than left with a page that quietly lacks the image.
                    Timber.w("Couldn't stage shared image %s", uri)
                    dropped++
                } else {
                    names += resolved
                }
            }
            return StagedImages(names = names, dropped = dropped)
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
 * What to tell the user after a share created a page, and whether anything is
 * left to do about it.
 *
 * Issue #25: a capture whose image references only reached the edit queue is
 * a success — the offline design working — but it is not the same success as
 * one already on the server, and reporting it as though it were is how a
 * partial result passes for a clean one.
 *
 * Issue #31: two more ways to end up short of that, both of which happen
 * *before* the reference append and were therefore invisible to #25's check.
 * The shared text may have failed its own write, and shared images may never
 * have been staged. Neither is a reason to claim a clean capture, and only
 * the reference append is worth offering a retry for.
 */
internal fun captureReport(
    created: PartialCreate,
    refsOutcome: SaveOutcome,
): CaptureReport {
    val title = created.pageTitle
    val lost = buildList {
        when (val body = created.bodyOutcome) {
            SaveOutcome.Saved -> Unit
            SaveOutcome.Queued -> add("the text will sync when online")
            SaveOutcome.Conflict -> add("the text is waiting as a draft")
            is SaveOutcome.Error -> add("the text didn't save (${body.message})")
        }
        when (refsOutcome) {
            SaveOutcome.Saved -> Unit
            SaveOutcome.Queued -> add("the image links will sync when online")
            SaveOutcome.Conflict -> add("the image links are waiting as a draft")
            is SaveOutcome.Error -> add("the image links didn't save (${refsOutcome.message})")
        }
        if (created.imagesDropped > 0) add(droppedImagesPhrase(created.imagesDropped))
    }
    // Nothing of the share reached the page at all. Worth its own arm: the
    // page exists but is empty, so "Saved" would be actively wrong.
    val savedNothing = created.imagesDropped > 0 &&
        created.imageRefs.isEmpty() &&
        !created.hasText
    return CaptureReport(
        message = when {
            savedNothing -> "Created \"$title\", but ${droppedImagesPhrase(created.imagesDropped)}"
            lost.isEmpty() -> "Saved as \"$title\""
            else -> "Saved as \"$title\" — ${lost.joinToString("; ")}"
        },
        succeeded = !savedNothing && refsOutcome !is SaveOutcome.Error,
        resumable = refsOutcome is SaveOutcome.Error,
    )
}

/** The append half of [captureReport]. */
internal fun appendReport(
    outcome: SaveOutcome,
    imagesDropped: Int,
): CaptureReport {
    val base = when (outcome) {
        SaveOutcome.Saved -> "Appended"
        SaveOutcome.Queued -> "Appended (queued, will sync when online)"
        SaveOutcome.Conflict -> "Page changed on the server; appended as a draft"
        is SaveOutcome.Error -> outcome.message
    }
    val succeeded = outcome !is SaveOutcome.Error
    return CaptureReport(
        message = if (succeeded && imagesDropped > 0) {
            "$base — ${droppedImagesPhrase(imagesDropped)}"
        } else {
            base
        },
        succeeded = succeeded,
        resumable = false,
    )
}

/** Nothing the share carried could be saved (issue #31). */
internal fun nothingToSaveMessage(imagesDropped: Int): String =
    if (imagesDropped > 0) {
        "Nothing to save — ${droppedImagesPhrase(imagesDropped)}"
    } else {
        "Nothing to save — the share was empty"
    }

private fun droppedImagesPhrase(count: Int): String =
    if (count == 1) {
        "1 image couldn't be read"
    } else {
        "$count images couldn't be read"
    }
