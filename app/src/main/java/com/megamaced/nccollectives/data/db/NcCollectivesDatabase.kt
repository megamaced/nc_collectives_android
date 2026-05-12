package com.megamaced.nccollectives.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity

@Database(
    entities = [
        CollectiveEntity::class,
        PageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NcCollectivesDatabase : RoomDatabase() {
    abstract fun collectiveDao(): CollectiveDao

    abstract fun pageDao(): PageDao
}
