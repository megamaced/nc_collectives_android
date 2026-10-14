package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.domain.model.TagCount
import org.junit.Assert.assertEquals
import org.junit.Test

class TagCountsTest {
    @Test
    fun countsPagesPerTag_mostUsedFirst_thenByName() {
        val counts =
            tagCounts(
                listOf(
                    joinTags(listOf("recipes", "Ideas")),
                    joinTags(listOf("recipes")),
                    joinTags(listOf("archive", "ideas")),
                ),
            )
        assertEquals(
            listOf(TagCount("recipes", 2), TagCount("archive", 1), TagCount("Ideas", 1), TagCount("ideas", 1)),
            counts,
        )
    }

    @Test
    fun aTagRepeatedOnOnePageCountsThatPageOnce() {
        assertEquals(listOf(TagCount("todo", 1)), tagCounts(listOf(joinTags(listOf("todo", "todo")))))
    }

    @Test
    fun noTagListsMeansNoTags() {
        assertEquals(emptyList<TagCount>(), tagCounts(emptyList()))
    }
}
