package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.PageViewEntity

@Dao
interface PageViewDao {
    @Query("SELECT * FROM page_views WHERE pageId = :pageId")
    suspend fun get(pageId: Long): PageViewEntity?

    @Upsert
    suspend fun upsert(view: PageViewEntity)

    /** Follow a page whose id the server reissued. See `PageRepositoryImpl.carryLocalStateToNewId`. */
    @Query("UPDATE page_views SET pageId = :newId WHERE pageId = :oldId")
    suspend fun repointPage(
        oldId: Long,
        newId: Long,
    )

    @Query("DELETE FROM page_views WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)

    @Query("DELETE FROM page_views WHERE pageId IN (:pageIds)")
    suspend fun deleteForPageIds(pageIds: List<Long>)

    @Query("DELETE FROM page_views")
    suspend fun clear()
}
