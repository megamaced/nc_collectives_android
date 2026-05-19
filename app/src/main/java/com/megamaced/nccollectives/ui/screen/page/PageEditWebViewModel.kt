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
         * activity finish path. Triggers a Room refresh so the server-side
         * autosaved body comes back into the cache; the screen then pops
         * the back stack.
         */
        fun onClose() {
            viewModelScope.launch {
                // Refresh the collective so the autosaved body, last-modified
                // timestamp, and any tag/emoji changes Text might have made
                // come back into Room. PageView's observe-page Flow will
                // then re-render with the fresh content.
                pageRepository.getPage(pageId)?.let { page ->
                    pageRepository.refresh(page.collectiveId)
                }
                _uiState.value = PageEditWebUiState.Closed
            }
        }
    }
