package com.megamaced.nccollectives.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity

@Database(
    entities = [
        CollectiveEntity::class,
        PageEntity::class,
        EditQueueEntity::class,
        AttachmentEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class NcCollectivesDatabase : RoomDatabase() {
    abstract fun collectiveDao(): CollectiveDao

    abstract fun pageDao(): PageDao

    abstract fun editQueueDao(): EditQueueDao

    abstract fun attachmentDao(): AttachmentDao
}
