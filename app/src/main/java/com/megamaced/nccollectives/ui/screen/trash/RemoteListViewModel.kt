package com.megamaced.nccollectives.ui.screen.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A trash listing. [items] is a server snapshot, not a Room flow — trashed
 * rows are cached nowhere, which is why restore and purge take the row out
 * locally instead of waiting for an observer to notice.
 */
data class RemoteListUiState<T>(
    val isLoading: Boolean = false,
    val items: List<T> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

/**
 * The page trash and the collective trash, minus the type they list.
 *
 * R-60: the two were line-for-line identical apart from `Page`/`Collective`
 * and four string literals. Subclasses supply the four calls and the two
 * confirmations; the load guard, the [ApiResult] mapping and the snapshot
 * bookkeeping live here.
 *
 * An abstract base rather than a composed helper because that is what Hilt
 * can see: `@HiltViewModel` reads the *subclass's* `@Inject` constructor, so
 * each entry point keeps its own repositories and `SavedStateHandle` while
 * sharing this. Nothing is injected here, so Dagger has no base-class
 * binding to resolve.
 *
 * Deliberately does not load from its own `init`: the first load runs from
 * the subclass's `init`, once the subclass's properties exist.
 * `viewModelScope` dispatches on `Main.immediate`, so a `launch` from a base
 * constructor can run its body synchronously — before `collectiveId` has
 * been assigned, which [load] would then read as 0.
 */
abstract class RemoteListViewModel<T> : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteListUiState<T>())
    val uiState: StateFlow<RemoteListUiState<T>> = _uiState.asStateFlow()

    /** Identity used to drop a restored or purged row from the snapshot. */
    protected abstract fun idOf(item: T): Long

    protected abstract suspend fun load(): ApiResult<List<T>>

    protected abstract suspend fun restoreItem(id: Long): ApiResult<Unit>

    protected abstract suspend fun purgeItem(id: Long): ApiResult<Unit>

    protected abstract val restoredMessage: String

    protected abstract val purgedMessage: String

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val listed = load()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    // A failed re-list keeps the rows it already had.
                    items = if (listed is ApiResult.Success) listed.data else it.items,
                    errorMessage = listed.userMessage(),
                )
            }
        }
    }

    fun restore(id: Long) {
        commit(id, restoredMessage) { restoreItem(it) }
    }

    fun purge(id: Long) {
        commit(id, purgedMessage) { purgeItem(it) }
    }

    fun dismissStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * Run a one-row operation and, on success, drop the row rather than
     * re-listing — the server has just said it is no longer in the trash.
     */
    private fun commit(
        id: Long,
        successMessage: String,
        action: suspend (Long) -> ApiResult<Unit>,
    ) {
        viewModelScope.launch {
            val result = action(id)
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(
                        statusMessage = successMessage,
                        items = state.items.filter { idOf(it) != id },
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = result.userMessage()) }
            }
        }
    }
}
