package com.megamaced.nccollectives.ui.screen.trash

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** One collective's trashed pages. */
@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: PageRepository,
    ) : RemoteListViewModel<Page>() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.Trash.ARG_COLLECTIVE_ID),
        )

        override val restoredMessage = "Page restored"
        override val purgedMessage = "Page permanently deleted"

        init {
            refresh()
        }

        override fun idOf(item: Page): Long = item.id

        override suspend fun load(): ApiResult<List<Page>> = repository.listTrashedPages(collectiveId)

        override suspend fun restoreItem(id: Long): ApiResult<Unit> = repository.restorePage(collectiveId, id)

        override suspend fun purgeItem(id: Long): ApiResult<Unit> = repository.purgePage(collectiveId, id)
    }
