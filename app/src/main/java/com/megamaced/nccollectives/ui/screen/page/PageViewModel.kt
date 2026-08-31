package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.model.OpenableAttachment
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.ui.screen.onFailureMessage
import com.megamaced.nccollectives.util.AttachmentRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Destination the Edit button should navigate to (Batch 29). */
enum class EditRoute { Plain, Web }

/**
 * Result of resolving the Edit button's destination. Carries an optional
 * [fallbackMessage] to surface as a snackbar when we couldn't honour the
 * user's setting exactly (e.g. `PreferCollaborative` on a server that
 * doesn't support it, or while offline).
 */
data class EditRouteDecision(
    val route: EditRoute,
    val fallbackMessage: String?,
)

data class PageViewUiState(
    val isLoadingBody: Boolean = false,
    /**
     * True only while a *user-initiated* body refresh is in flight — the
     * pull-to-refresh indicator's flag. Deliberately not [isLoadingBody]:
     * that one is also true for the automatic revalidation every page open
     * fires from `init`, so binding the indicator to it would flash the
     * refresh spinner every single time a page is opened.
     */
    val isRefreshingBody: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    /** Available tags for the page's collective; populated when the tags sheet is opened. */
    val availableTags: List<PageTag> = emptyList(),
    val isLoadingTags: Boolean = false,
    /** Pages in the same collective, used as targets for the move sheet. */
    val movableTargets: List<Page> = emptyList(),
    /**
     * Set to the new page id after a successful duplicate (Batch 23). The
     * UI shows a "Copied — Open?" snackbar and clears this via
     * [acknowledgeCopied] once it's been surfaced.
     */
    val copiedPageId: Long? = null,
    /** Filename of an attachment currently being downloaded for viewing. */
    val downloadingAttachment: String? = null,
    /**
     * True while the "Moved to trash" snackbar is owed to the user. The page
     * has already been trashed by then (B-75) — the snackbar's action calls
     * [PageViewModel.restoreTrashed], its timeout
     * [PageViewModel.acknowledgeTrashed].
     */
    val trashedAwaitingUndo: Boolean = false,
    /**
     * Attachment staged and ready to hand off. The screen fires the
     * `ACTION_VIEW` intent (a ViewModel has no business starting
     * activities) and clears this via [acknowledgeAttachmentOpened].
     */
    val attachmentToOpen: OpenableAttachment? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val pageRepository: PageRepository,
        private val collectiveRepository: CollectiveRepository,
        private val attachmentRepository: AttachmentRepository,
        private val directEditingRepository: DirectEditingRepository,
        private val userPreferences: UserPreferences,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageView.ARG_PAGE_ID),
        )

        val page: StateFlow<Page?> = pageRepository.observePage(pageId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

        private val _imageBaseUrl = MutableStateFlow<String?>(null)
        val imageBaseUrl: StateFlow<String?> = _imageBaseUrl.asStateFlow()

        /**
         * Count of attachments on this page that have successfully uploaded
         * (status = `REMOTE`). Bumps whenever a queued upload completes —
         * the page screen keys the markdown view on this so Markwon
         * re-fetches images that previously 404'd while their upload was
         * still in flight (B-56).
         */
        val remoteAttachmentCount: StateFlow<Int> = attachmentRepository
            .observeForPage(pageId)
            .map { list -> list.count { it.status == Attachment.Status.REMOTE } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

        val isFavorite: StateFlow<Boolean> = combine(
            page,
            collectiveRepository.observeCollectives(),
        ) { p, collectives ->
            p?.let { current ->
                collectives.firstOrNull { it.id == current.collectiveId }?.favoritePageIds?.contains(current.id) ?: false
            } ?: false
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        val backlinks: StateFlow<List<Page>> = page
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    pageRepository.observeBacklinksFor(current.collectiveId, current.id)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        private val _uiState = MutableStateFlow(PageViewUiState())
        val uiState: StateFlow<PageViewUiState> = _uiState.asStateFlow()

        /**
         * Collective the page was trashed out of. Captured before the commit
         * because trashing drops the local row, and `restorePage` is keyed on
         * the collective as well as the page.
         */
        private var trashedFromCollectiveId: Long? = null

        init {
            viewModelScope.launch {
                _imageBaseUrl.value = attachmentRepository.attachmentsBaseUrl(pageId)
            }
            // B-58: revalidate on every open, not just when the body is
            // missing. The old `if (cached.bodyMd == null)` gate meant a body
            // was fetched exactly once and then never again — nothing else in
            // the app re-fetches page markdown, so a page edited anywhere else
            // stayed frozen at whatever this device first saw. The conditional
            // request makes the no-change case a 304.
            refreshBody(userInitiated = false)
        }

        /**
         * Pull the current body from the server, updating the cache if it has
         * changed.
         *
         * [userInitiated] separates "the user asked" from "we're checking
         * because the page just opened". An automatic check that fails while
         * a cached body is already on screen stays silent: offline is the
         * normal state for this app, and a network snackbar on every page
         * open would be noise, not information. A user-initiated refresh
         * always reports — including "Already up to date", which is the
         * answer they were actually asking for.
         */
        fun refreshBody(userInitiated: Boolean = true) {
            if (_uiState.value.isLoadingBody) return
            _uiState.update {
                it.copy(
                    isLoadingBody = true,
                    isRefreshingBody = userInitiated,
                    errorMessage = null,
                )
            }
            viewModelScope.launch {
                val hadCachedBody = pageRepository.getPage(pageId)?.bodyMd != null
                val result = pageRepository.refreshBodyIfChanged(pageId)
                val failure = result.userMessage()
                _uiState.update { state ->
                    state.copy(
                        isLoadingBody = false,
                        isRefreshingBody = false,
                        // B-81: `errorMessage` drives the full-screen error
                        // state, so it only carries a failure with nothing
                        // behind it. Over a cached body that arm never
                        // renders and the message used to reach the user
                        // only because this screen alone also snackbarred
                        // `errorMessage`; it goes via `statusMessage` now.
                        errorMessage = if (hadCachedBody) null else failure,
                        statusMessage = when {
                            !userInitiated -> state.statusMessage
                            failure != null -> if (hadCachedBody) failure else state.statusMessage
                            result is ApiResult.Success && result.data -> "Page updated"
                            else -> "Already up to date"
                        },
                    )
                }
            }
        }

        /**
         * Resolve the Edit button's destination based on the user's
         * [EditorPreference] setting and runtime capability discovery.
         * Suspending because the capability lookup may hit the network
         * on the first call per session (memoised after).
         *
         * Returns the route to navigate to, plus a user-facing snackbar
         * if we couldn't honour the preference exactly — i.e. user set
         * `PreferCollaborative` but the server doesn't expose
         * `directEditing`, or the device is offline. Logs the decision
         * at debug level via Timber — no analytics endpoint, ever.
         */
        suspend fun resolveEditRoute(): EditRouteDecision {
            val preference = userPreferences.flow.first().editorPreference
            return when (preference) {
                EditorPreference.PreferPlain -> {
                    Timber.tag(TAG).d("Edit route: plain (preference=PreferPlain)")
                    EditRouteDecision(EditRoute.Plain, fallbackMessage = null)
                }

                EditorPreference.PreferCollaborative -> {
                    val available = directEditingRepository.isAvailable()
                    if (available) {
                        Timber.tag(TAG).d("Edit route: web (preference=PreferCollaborative, server=available)")
                        EditRouteDecision(EditRoute.Web, fallbackMessage = null)
                    } else {
                        Timber
                            .tag(TAG)
                            .d("Edit route: plain (preference=PreferCollaborative, server=unavailable; surfacing toast)")
                        EditRouteDecision(
                            route = EditRoute.Plain,
                            fallbackMessage = "Collaborative editor unavailable — opening plain editor.",
                        )
                    }
                }
            }
        }

        fun toggleFavorite() {
            val current = page.value ?: return
            val want = !isFavorite.value
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = current.collectiveId,
                    pageId = current.id,
                    favorite = want,
                )
                result.onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        fun setEmoji(emoji: String) {
            viewModelScope.launch {
                pageRepository.setEmoji(pageId, emoji).onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        fun loadAvailableTags() {
            val current = page.value ?: return
            if (_uiState.value.isLoadingTags) return
            _uiState.update { it.copy(isLoadingTags = true) }
            viewModelScope.launch {
                val result = pageRepository.listTagsForCollective(current.collectiveId)
                _uiState.update { state ->
                    state.copy(
                        isLoadingTags = false,
                        availableTags = if (result is ApiResult.Success) result.data else state.availableTags,
                        statusMessage = if (result is ApiResult.Success) state.statusMessage else result.userMessage(),
                    )
                }
            }
        }

        fun togglePageTag(
            tag: PageTag,
            add: Boolean,
        ) {
            viewModelScope.launch {
                pageRepository.togglePageTag(pageId, tag.id, tag.name, add).onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        /**
         * Create a tag on the current page's collective and immediately
         * attach it to the page (OCS-5, Batch 18k). The colour is fixed
         * to the Nextcloud brand blue — Collectives uses the colour only
         * as a visual marker and the tag picker doesn't surface it yet.
         */
        fun createTag(name: String) {
            val current = page.value ?: return
            val cleaned = name.trim()
            if (cleaned.isEmpty()) return
            viewModelScope.launch {
                val createResult = pageRepository.createTag(
                    collectiveId = current.collectiveId,
                    name = cleaned,
                    color = NEW_TAG_COLOUR,
                )
                when (createResult) {
                    is ApiResult.Success -> {
                        val created = createResult.data
                        _uiState.update { state ->
                            val nextAvailable = (state.availableTags + created)
                                .distinctBy { it.id }
                                .sortedBy { it.name.lowercase() }
                            state.copy(availableTags = nextAvailable)
                        }
                        // Attach the new tag to the current page so the user
                        // doesn't have to click again.
                        togglePageTag(created, add = true)
                    }

                    else -> {
                        _uiState.update {
                            it.copy(statusMessage = createResult.userMessage() ?: "Couldn't create tag")
                        }
                    }
                }
            }
        }

        fun renamePage(newTitle: String) {
            viewModelScope.launch {
                val result = pageRepository.renamePage(pageId, newTitle)
                _uiState.update { it.copy(statusMessage = renameOrMoveMessage(result, "renamed")) }
            }
        }

        fun loadMoveTargets() {
            val current = page.value ?: return
            viewModelScope.launch {
                // `.first()` takes a snapshot and unsubscribes — previously
                // `.collect { … return@collect }` left a Room observer running
                // for the screen's lifetime (B-7).
                val list = pageRepository.observePages(current.collectiveId).first()
                // B-39: drop the moved page and its descendants — a cycle
                // (move A under one of A's own children) bricks the page
                // tree on the server. Walk parents toward the root, stop
                // at a malformed chain (parentId → missing row).
                val byId = list.associateBy { it.id }
                val descendantIds = buildSet {
                    for (candidate in list) {
                        var cursor: Page? = byId[candidate.parentId]
                        while (cursor != null) {
                            if (cursor.id == pageId) {
                                add(candidate.id)
                                break
                            }
                            cursor = byId[cursor.parentId]
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        movableTargets = list.filter { p -> p.id != pageId && p.id !in descendantIds },
                    )
                }
            }
        }

        fun movePage(newParentPageId: Long) {
            viewModelScope.launch {
                val result = pageRepository.movePage(pageId, newParentPageId)
                _uiState.update { it.copy(statusMessage = renameOrMoveMessage(result, "moved")) }
            }
        }

        fun duplicatePage() {
            val current = page.value ?: return
            viewModelScope.launch {
                val result = pageRepository.copyPage(current.collectiveId, current.id)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success -> {
                            it.copy(copiedPageId = result.data.id)
                        }

                        else -> {
                            it.copy(statusMessage = result.userMessage())
                        }
                    }
                }
            }
        }

        fun acknowledgeCopied() {
            _uiState.update { it.copy(copiedPageId = null) }
        }

        /**
         * Move the page to the collective's trash, now, and offer an undo
         * afterwards.
         *
         * B-75: the commit used to run *after* `showSnackbar` returned inside
         * a `LaunchedEffect` on the screen — a coroutine that dies with the
         * composition. Confirming the trash and immediately pressing Back
         * cancelled it before the server was ever told, so the user saw
         * "Moved to trash" over a page that was still there; meanwhile the
         * `rememberSaveable` flag that armed it survived, re-fired on the next
         * visit, and trashed the page 4s later out of nowhere. A delayed
         * commit can't live in a composition, and it can't live in
         * `viewModelScope` either — popping the nav entry clears this
         * ViewModel just as fast. So the trash is committed here and the
         * snackbar's action puts the page back.
         */
        fun trashPage() {
            if (_uiState.value.trashedAwaitingUndo) return
            // Read while the row is still cached: `trashPage` drops it, and
            // `restorePage` needs the collective.
            val collectiveId = page.value?.collectiveId
            viewModelScope.launch {
                val result = pageRepository.trashPage(pageId)
                if (result is ApiResult.Success) {
                    trashedFromCollectiveId = collectiveId
                    _uiState.update { it.copy(trashedAwaitingUndo = true) }
                } else {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        /** Undo tapped — restore the page and stay on it. */
        fun restoreTrashed() {
            _uiState.update { it.copy(trashedAwaitingUndo = false) }
            val collectiveId = trashedFromCollectiveId
            if (collectiveId == null) {
                _uiState.update { it.copy(statusMessage = "Couldn't restore the page") }
                return
            }
            viewModelScope.launch {
                val result = pageRepository.restorePage(collectiveId, pageId)
                trashedFromCollectiveId = null
                _uiState.update {
                    it.copy(
                        statusMessage = if (result is ApiResult.Success) {
                            "Page restored"
                        } else {
                            result.userMessage() ?: "Couldn't restore the page"
                        },
                    )
                }
            }
        }

        /** Undo window closed without a tap — the trash stands. */
        fun acknowledgeTrashed() {
            trashedFromCollectiveId = null
            _uiState.update { it.copy(trashedAwaitingUndo = false) }
        }

        fun replaceWithDraft() {
            val draft = page.value?.draftBodyMd ?: return
            viewModelScope.launch {
                val outcome = pageRepository.replaceWithDraft(pageId, draft)
                _uiState.update { it.copy(statusMessage = saveOutcomeMessage(outcome)) }
            }
        }

        fun discardDraft() {
            viewModelScope.launch {
                pageRepository.discardDraft(pageId)
                // B-78: confirm the destruction actually happened. The banner
                // simply vanishing left no trace of an irreversible action.
                _uiState.update { it.copy(statusMessage = "Draft discarded") }
            }
        }

        /**
         * B-81: the snackbar message only. `errorMessage` belongs to the
         * full-screen error state and is cleared by the next [refreshBody];
         * clearing it here swapped a retryable error screen for an empty
         * page the moment the snackbar timed out.
         */
        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }

        /**
         * Resolve a wikilink (or relative markdown reference) to a page id in
         * the same collective. Returns null if the target isn't cached — the
         * caller should fall back to a status message.
         */
        fun resolveWikilink(
            target: String,
            onResolved: (Long) -> Unit,
        ) {
            val current = page.value ?: return
            viewModelScope.launch {
                val resolved = pageRepository.resolvePageByTitle(current.collectiveId, target)
                if (resolved != null) {
                    onResolved(resolved)
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "Linked page \"$target\" not found")
                    }
                }
            }
        }

        /**
         * The user tapped a link pointing at one of this page's attachments.
         * Non-image attachments (PDFs, office documents, archives) can't be
         * rendered inline, so we download the file and hand it to whichever
         * app the user has for that type.
         *
         * Guarded against re-entry: staging a multi-MB PDF takes long enough
         * that an impatient double-tap would otherwise start a second
         * download writing into the same cache file as the first.
         */
        fun openAttachment(ref: AttachmentRef) {
            if (_uiState.value.downloadingAttachment != null) return
            _uiState.update { it.copy(downloadingAttachment = ref.fileName) }
            viewModelScope.launch {
                val result = attachmentRepository.downloadForViewing(pageId, ref.relativePath)
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
                                statusMessage = result.userMessage()
                                    ?: "Couldn't open ${ref.fileName}",
                            )
                        }
                    }
                }
            }
        }

        /**
         * Called once the screen has fired the view intent. [failureMessage]
         * is non-null when nothing on the device could open the file — the
         * user gets told rather than watching a tap do nothing.
         */
        fun acknowledgeAttachmentOpened(failureMessage: String? = null) {
            _uiState.update {
                it.copy(
                    attachmentToOpen = null,
                    statusMessage = failureMessage ?: it.statusMessage,
                )
            }
        }

        private fun renameOrMoveMessage(
            result: ApiResult<Unit>,
            verb: String,
        ): String? =
            when (result) {
                is ApiResult.Success -> "Page $verb"
                else -> result.userMessage()
            }

        private fun saveOutcomeMessage(outcome: SaveOutcome): String? =
            when (outcome) {
                SaveOutcome.Saved -> "Page replaced"
                SaveOutcome.Queued -> "Saved offline — will sync when online"
                SaveOutcome.Conflict -> "Still conflicting — try again later"
                is SaveOutcome.Error -> outcome.message
            }

        private companion object {
            const val TAG = "PageViewModel"

            /**
             * Default colour for tags created in-app — 6-hex without `#`
             * per `ENDPOINTS.md` gotcha #2. Matches the M3 primary
             * (`0xFF0082C9` in [com.megamaced.nccollectives.ui.theme.Color]),
             * which is the canonical Nextcloud brand blue.
             */
            const val NEW_TAG_COLOUR = "0082c9"
        }
    }
