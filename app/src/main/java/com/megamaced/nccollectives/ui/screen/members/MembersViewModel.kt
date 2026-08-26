package com.megamaced.nccollectives.ui.screen.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.DEFAULT_MEMBER_LIMIT
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.isRetryableFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ordering for the members list: most privileged first, then by name (B-90).
 *
 * Matches what the web app does, so the same team reads the same way in both
 * clients — and it is the ordering the list is actually *for*: "who runs
 * this collective" is answerable from the top of the screen without
 * scrolling, which an alphabetical list can't do.
 *
 * Compares on `CollectiveMemberLevel.raw` rather than on the enum's natural
 * (declaration) ordering. Both give the same answer today; `raw` keeps
 * giving it if a future Circles level is declared in the wrong slot. Owner
 * (9) sorts above `Unknown` (0), which is the point of `Unknown` being 0 —
 * a level the server didn't send must never float to the top of a list of
 * who has power over the collective.
 *
 * [CollectiveMember.label] is the name key, not `displayName`: mail and
 * contact members legitimately have a blank display name, and sorting on
 * the raw field would collect all of them in one lump at the start
 * regardless of who they are. `label` is never blank, so the comparator has
 * no empty-key case to think about.
 *
 * `id` is the final tiebreak so the order is total — two same-level members
 * with the same display name (a real possibility: display names are not
 * unique on a Nextcloud) would otherwise sort non-deterministically, and a
 * list that reshuffles between loads looks broken.
 */
internal val MEMBER_ORDER: Comparator<CollectiveMember> =
    compareByDescending<CollectiveMember> { it.level.raw }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        .thenBy { it.id }

/**
 * A server snapshot of one collective's membership, and how it got there.
 *
 * [canRetry] is carried separately from [errorMessage] because on this
 * screen the difference between "try again" and "that is the answer" is the
 * whole design — see [isRetryableFailure]. The screen turns it into the
 * presence or absence of a Retry button; nothing else distinguishes a
 * refusal from a dropped connection.
 *
 * [mayHaveMore] is not "there are more". The endpoint returns no total, so a
 * full page of results is the only evidence available and it is equally
 * consistent with a team of exactly [DEFAULT_MEMBER_LIMIT].
 */
data class MembersUiState(
    val isLoading: Boolean = false,
    val collectiveName: String = "",
    val members: List<CollectiveMember> = emptyList(),
    val errorMessage: String? = null,
    val canRetry: Boolean = true,
    val mayHaveMore: Boolean = false,
)

/**
 * The members list for one collective.
 *
 * Written from scratch rather than on `RemoteListViewModel`, which is the
 * near-fit it looks like: that base class keys its snapshot on
 * `idOf(item): Long` and `CollectiveMember.id` is a `String`, and its whole
 * reason for existing — the restore/purge pair that drops a row optimistically
 * — is a mutation shape this read-only screen has none of. Widening the base
 * class to fit would have made both trash screens generic in an id type
 * neither of them has, to share a load guard that is three lines.
 *
 * Online-only by design: nothing here is written to Room. See
 * `CollectiveRepository.listMembers` for why (small, rarely-read,
 * privacy-adjacent lists that the web app doesn't cache either), and
 * `CollectiveMembersTest`, whose strict DAO mocks fail if that ever changes.
 */
@HiltViewModel
class MembersViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectiveRepository: CollectiveRepository,
    ) : ViewModel() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.Members.ARG_COLLECTIVE_ID),
        )

        // Starts loading: the collective arrives from Room on the first
        // emission below, so the alternative is a frame of "no members" under
        // a screen that is about to have some.
        private val _uiState = MutableStateFlow(MembersUiState(isLoading = true))
        val uiState: StateFlow<MembersUiState> = _uiState.asStateFlow()

        /**
         * The circle [uiState] holds members for, or null before the first
         * load. This is what stops the collector below re-listing on every
         * unrelated write to the `collectives` table — favouriting a page in
         * this collective rewrites its row — which matters more here than on
         * other screens, because the endpoint being re-hit is the one that
         * throttles the user's own IP.
         */
        private var listedCircleId: String? = null

        init {
            viewModelScope.launch {
                // Observed, not snapshotted with `.first()` as `TagBrowseViewModel`
                // does for the collective name (R-35). `circleId` is not stable
                // for the screen's lifetime the way a name is: `MIGRATION_8_9`
                // added the column as nullable, so every row cached by an older
                // build reads back null until the `refresh()` that runs on app
                // open fills it in. A snapshot taken in that window would leave
                // the screen permanently saying membership is unavailable when it
                // is seconds away from being available.
                collectiveRepository
                    .observeCollectives()
                    .map { list -> list.firstOrNull { it.id == collectiveId } }
                    .distinctUntilChanged()
                    .collect { collective -> onCollective(collective) }
            }
        }

        /**
         * Ask the server again. Refuses while a load is in flight, and
         * refuses outright once a terminal failure has landed.
         *
         * The terminal guard is deliberately here and not only in the screen's
         * missing Retry button: "never re-ask after a 403" is a rule about the
         * request, so it holds for any caller — a pull-to-refresh added later,
         * a test, a debug action — rather than depending on which affordances
         * happen to be drawn.
         */
        fun refresh() {
            val state = _uiState.value
            if (state.isLoading || !state.canRetry) return
            val circleId = listedCircleId ?: return
            load(circleId)
        }

        /**
         * React to the cached collective. Everything this screen needs is
         * decided here: whether there is a team to ask about at all, and
         * whether it is a different one from the one already on screen.
         */
        private fun onCollective(collective: Collective?) {
            if (collective == null) {
                // Not "no members" — the collective itself has left the cache,
                // trashed or unshared while this screen was open. There is
                // nothing to resolve a circle id from, and re-asking would
                // query a collective the user can no longer see.
                terminal("This collective isn't available any more.")
                return
            }
            _uiState.update { it.copy(collectiveName = collective.name) }
            val circleId = collective.circleId
            if (circleId == null) {
                // No request is made. `listMembers` would answer
                // `ApiResult.Unexpected` for a blank id anyway, but the honest
                // reading of a null here is a cache row older than
                // `MIGRATION_8_9`: the next `refresh()` fills the column in,
                // this collector fires again, and `load` clears this state
                // itself. Nothing for the user to retry in the meantime.
                terminal(
                    "This device doesn't know which team backs this collective yet. " +
                        "Refreshing your collectives fills that in.",
                )
                return
            }
            if (circleId == listedCircleId) return
            listedCircleId = circleId
            load(circleId)
        }

        /**
         * [DEFAULT_MEMBER_LIMIT] and not something larger. 100 members is
         * ~220 KB of JSON; the endpoint sends no total, so a bigger cap buys
         * no extra *information* about what is missing, it only moves the
         * truncation further down a list a phone shows ten rows of at a time.
         * `MembersUiState.mayHaveMore` is the honest answer to "is that all of
         * them", and it costs nothing on the wire.
         */
        private fun load(circleId: String) {
            // Resets the terminal state as well as the message: reaching here
            // means something changed that makes asking worthwhile again.
            _uiState.update { it.copy(isLoading = true, errorMessage = null, canRetry = true) }
            viewModelScope.launch {
                val listed = collectiveRepository.listMembers(circleId, DEFAULT_MEMBER_LIMIT)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        // A failed re-list keeps the rows it already had, the
                        // same way `RemoteListViewModel` does — a blank screen
                        // where the user's data was is worse than a stale one.
                        members = if (listed is ApiResult.Success) {
                            listed.data.sortedWith(MEMBER_ORDER)
                        } else {
                            state.members
                        },
                        mayHaveMore = listed is ApiResult.Success &&
                            listed.data.size >= DEFAULT_MEMBER_LIMIT,
                        errorMessage = listed.userMessage(),
                        canRetry = isRetryableFailure(listed),
                    )
                }
            }
        }

        /** A failure with no request behind it and nothing to retry. */
        private fun terminal(message: String) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = message, canRetry = false)
            }
        }
    }
