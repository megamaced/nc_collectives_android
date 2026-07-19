package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for [PageEditWebScreen]. The `Loaded(url)` state carries the
 * single-use signed URL returned by `directediting/open`; reloading the
 * WebView with that URL (config change, process death) invalidates the
 * token and forces a re-request, which is why neither this state nor
 * the URL itself is `rememberSaveable` — every fresh ViewModel instance
 * fetches a fresh URL.
 */
sealed interface PageEditWebUiState {
    data object Loading : PageEditWebUiState

    data class Loaded(
        val url: String,
    ) : PageEditWebUiState

    /** Loaded once but the JS bridge has reported `loaded()`. */
    data class Interactive(
        val url: String,
    ) : PageEditWebUiState

    data class Failed(
        val message: String,
    ) : PageEditWebUiState

    data object Closed : PageEditWebUiState
}

@HiltViewModel
class PageEditWebViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val directEditingRepository: DirectEditingRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageEditWeb.ARG_PAGE_ID),
        )

        private val _uiState = MutableStateFlow<PageEditWebUiState>(PageEditWebUiState.Loading)
        val uiState: StateFlow<PageEditWebUiState> = _uiState.asStateFlow()

        init {
            requestSession()
        }

        /**
         * Fire the OCS `directediting/open` request. Called once on init;
         * may be re-called explicitly on retry. The returned URL is a
         * one-shot token-bearing URL; reloading the WebView with the same
         * URL after `Interactive` has been reached will 410 / redirect to
         * login, so re-entries to the screen create a fresh ViewModel,
         * which fires this again.
         */
        fun requestSession() {
            _uiState.value = PageEditWebUiState.Loading
            viewModelScope.launch {
                val page = pageRepository.getPage(pageId)
                if (page == null) {
                    _uiState.value = PageEditWebUiState.Failed("Page is no longer cached locally")
                    return@launch
                }
                when (val result = directEditingRepository.openSession(page)) {
                    is ApiResult.Success ->
                        _uiState.value = PageEditWebUiState.Loaded(result.data)
                    else ->
                        _uiState.value = PageEditWebUiState.Failed(
                            result.userMessage() ?: "Couldn't open the collaborative editor",
                        )
                }
            }
        }

        /**
         * Surface a load-time failure from outside the OCS request path
         * (Batch 30c — e.g. WebView SSL error). Transitions to
         * [PageEditWebUiState.Failed] so the screen's snackbar fires.
         */
        fun surfaceLoadFailure(message: String) {
            _uiState.value = PageEditWebUiState.Failed(message)
        }

        /**
         * Called by the JS bridge (`reload()`) when Text reports the editing
         * session was invalidated server-side (its `onPushForbidden` path,
         * new at Text v34). The one-shot `directediting/open` token behind
         * the current URL is dead, so we re-request a fresh session rather
         * than reloading the stale URL (which would 410 / bounce to login).
         * `requestSession()` flips state to `Loading`, which tears down the
         * current WebView; the subsequent `Loaded(freshUrl)` mounts a new
         * one — same path a retry or a fresh VM instance takes.
         */
        fun onReloadRequested() {
            requestSession()
        }

        /** Called by the JS bridge once the editor JS has finished bootstrap. */
        fun onEditorReady() {
            _uiState.update { state ->
                when (state) {
                    is PageEditWebUiState.Loaded -> PageEditWebUiState.Interactive(state.url)
                    else -> state
                }
            }
        }

        /**
         * Called from the JS bridge (`close()`), the back press, or the
         * activity finish path. Pulls the server-side autosaved body back
         * into Room so PageView's observe-page Flow re-emits with the
         * fresh content, then transitions to Closed so the screen pops.
         *
         * **Why fetchBody and not just refresh:** `PageRepository.refresh`
         * upserts the page-list metadata but **preserves** the cached body
         * (`existingBody = existing.bodyMd`), so the Room row's bodyMd
         * stays stale — and the read-only view that re-renders on pop
         * shows the pre-edit content even though Text autosaved on the
         * server. `fetchBody` is the WebDAV round-trip that actually
         * replaces the body in Room. We still call refresh so any
         * sibling/tree changes Text might have made (rename, emoji)
         * come back too.
         */
        fun onClose() {
            viewModelScope.launch {
                pageRepository.getPage(pageId)?.let { page ->
                    pageRepository.refresh(page.collectiveId)
                }
                pageRepository.fetchBody(pageId)
                _uiState.value = PageEditWebUiState.Closed
            }
        }
    }
