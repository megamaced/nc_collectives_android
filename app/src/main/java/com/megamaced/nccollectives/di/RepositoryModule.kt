package com.megamaced.nccollectives.di

import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.ExpiredSessionHandler
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.data.repository.DirectEditingRepositoryImpl
import com.megamaced.nccollectives.data.repository.PageRepositoryImpl
import com.megamaced.nccollectives.data.repository.SearchRepositoryImpl
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.domain.repository.SearchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindCollectiveRepository(impl: CollectiveRepositoryImpl): CollectiveRepository

    @Binds
    abstract fun bindPageRepository(impl: PageRepositoryImpl): PageRepository

    @Binds
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    abstract fun bindAttachmentRepository(impl: AttachmentRepositoryImpl): AttachmentRepository

    @Binds
    abstract fun bindDirectEditingRepository(impl: DirectEditingRepositoryImpl): DirectEditingRepository

    /**
     * Issue #19: `SessionManager` notices an expired credential but must not
     * depend on `AccountSwitcher`, which depends on it. It asks for the
     * interface through a `dagger.Lazy` instead, and this is what satisfies
     * it.
     */
    @Binds
    abstract fun bindExpiredSessionHandler(impl: AccountSwitcher): ExpiredSessionHandler
}
