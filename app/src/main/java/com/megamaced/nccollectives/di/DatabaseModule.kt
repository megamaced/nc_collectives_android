package com.megamaced.nccollectives.di

import android.content.Context
import androidx.room.Room
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): NcCollectivesDatabase =
        Room
            .databaseBuilder(context, NcCollectivesDatabase::class.java, "nc_collectives.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideCollectiveDao(db: NcCollectivesDatabase): CollectiveDao = db.collectiveDao()

    @Provides fun providePageDao(db: NcCollectivesDatabase): PageDao = db.pageDao()
}
