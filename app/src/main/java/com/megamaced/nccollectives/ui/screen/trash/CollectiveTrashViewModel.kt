package com.megamaced.nccollectives.ui.screen.trash

import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.util.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Trashed collectives, across the whole account. */
@HiltViewModel
class CollectiveTrashViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
    ) : RemoteListViewModel<Collective>() {
        override val restoredMessage = uiText(R.string.trash_collective_restored)
        override val purgedMessage = uiText(R.string.trash_collective_deleted)

        init {
            refresh()
        }

        override fun idOf(item: Collective): Long = item.id

        override suspend fun load(): ApiResult<List<Collective>> = repository.listTrashedCollectives()

        override suspend fun restoreItem(id: Long): ApiResult<Unit> = repository.restoreTrashedCollective(id)

        override suspend fun purgeItem(id: Long): ApiResult<Unit> = repository.permanentlyDeleteCollective(id)
    }
