package com.megamaced.nccollectives.di

import android.content.Context
import androidx.room.Room
import com.megamaced.nccollectives.data.db.ALL_MIGRATIONS
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
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
            // Real migrations land in Batch 18m, replacing the destructive
            // fallback. See `data/db/Migrations.kt` for the per-version SQL.
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideCollectiveDao(db: NcCollectivesDatabase): CollectiveDao = db.collectiveDao()

    @Provides fun providePageDao(db: NcCollectivesDatabase): PageDao = db.pageDao()

    @Provides fun provideEditQueueDao(db: NcCollectivesDatabase): EditQueueDao = db.editQueueDao()

    @Provides fun provideAttachmentDao(db: NcCollectivesDatabase): AttachmentDao = db.attachmentDao()
}
