package com.megamaced.nccollectives.di

import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.data.repository.PageRepositoryImpl
import com.megamaced.nccollectives.data.repository.SearchRepositoryImpl
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
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
}
