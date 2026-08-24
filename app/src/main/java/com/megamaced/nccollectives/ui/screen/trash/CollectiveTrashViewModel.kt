package com.megamaced.nccollectives.ui.screen.trash

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Trashed collectives, across the whole account. */
@HiltViewModel
class CollectiveTrashViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
    ) : RemoteListViewModel<Collective>() {
        override val restoredMessage = "Collective restored"
        override val purgedMessage = "Collective permanently deleted"

        init {
            refresh()
        }

        override fun idOf(item: Collective): Long = item.id

        override suspend fun load(): ApiResult<List<Collective>> = repository.listTrashedCollectives()

        override suspend fun restoreItem(id: Long): ApiResult<Unit> = repository.restoreTrashedCollective(id)

        override suspend fun purgeItem(id: Long): ApiResult<Unit> = repository.permanentlyDeleteCollective(id)
    }
