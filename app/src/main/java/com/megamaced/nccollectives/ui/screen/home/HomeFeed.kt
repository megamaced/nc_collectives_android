package com.megamaced.nccollectives.ui.screen.home

import androidx.annotation.StringRes
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageAttention
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.TagCount
import com.megamaced.nccollectives.domain.repository.PageRepository
import kotlinx.coroutines.flow.Flow

/**
 * Home's sections with a full list behind "See all", in the order Home
 * shows them. [key] is the route argument, so it must not change.
 */
enum class HomeSection(
    val key: String,
    @param:StringRes val title: Int,
) {
    Attention("attention", R.string.home_section_attention),
    JumpBackIn("jump-back-in", R.string.home_section_jump_back_in),
    UpdatedSinceRead("updated-since-read", R.string.home_section_updated_since_read),
    RecentlyUpdated("recently-updated", R.string.home_section_recently_updated),
    YourEdits("your-edits", R.string.home_section_your_edits),
    ;

    companion object {
        fun fromKey(key: String?): HomeSection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Everything Home shows, read from the cache.
 *
 * Each list holds one row more than Home previews when there is more to
 * see, which is how Home knows to offer "See all" without counting. The
 * identity sections, [updatedSinceRead] and [yourEdits], stay empty until
 * the server has said who the user is.
 */
data class HomeFeed(
    val attention: PageAttention = PageAttention(conflicts = emptyList(), queued = emptyList()),
    val jumpBackIn: List<PageListItem> = emptyList(),
    val updatedSinceRead: List<PageListItem> = emptyList(),
    val recentlyUpdated: List<PageListItem> = emptyList(),
    val yourEdits: List<PageListItem> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    /** Alerts not yet seen in the Alerts tab, offered at the top of [attention]'s section. */
    val unseenAlerts: Int = 0,
) {
    /**
     * Nothing in any section. The recent list is only ever narrowed by the
     * collective filter, so an empty filtered list doesn't make Home empty:
     * the chips stay up to widen it again.
     */
    fun isEmpty(recentFiltered: Boolean): Boolean =
        unseenAlerts == 0 &&
            attention.isEmpty &&
            jumpBackIn.isEmpty() &&
            updatedSinceRead.isEmpty() &&
            (recentlyUpdated.isEmpty() && !recentFiltered) &&
            yourEdits.isEmpty() &&
            tags.isEmpty()
}

/** How many rows "Needs attention" shows on Home before "See all". */
internal const val HOME_PREVIEW_ROWS = 5

/** How many cards a card section shows on Home before "See all": three rows of two on a phone. */
internal const val HOME_PREVIEW_CARDS = 6

/** Tag chips shown before "Show all tags". */
internal const val HOME_PREVIEW_TAGS = 12

/** Rows on a "See all" screen. */
internal const val HOME_SECTION_LIMIT = 100

/** The collective filter value that means every collective. */
internal const val ALL_COLLECTIVES = 0L

/** The collective filter is worth offering only when there is a choice. */
internal fun showsCollectiveFilter(collectives: List<Collective>): Boolean = collectives.size >= 2

/**
 * The collective "Recently updated" is narrowed to, or null for all: the
 * [selected] one while it is still a live collective, so a filter left on a
 * collective that has since gone falls back to everything rather than to a
 * list that can never fill.
 */
internal fun effectiveCollectiveFilter(
    selected: Long,
    collectives: List<Collective>,
): Long? = selected.takeIf { id -> id != ALL_COLLECTIVES && collectives.any { it.id == id } }

/**
 * Whether a saved filter of [saved] should be forgotten: it names a
 * collective the loaded [live] list no longer has. Forgotten rather than
 * just ignored, so a collective restored later doesn't bring the filter back
 * with it.
 */
internal fun isStaleCollectiveFilter(
    saved: Long,
    live: List<Collective>,
): Boolean = saved != ALL_COLLECTIVES && live.none { it.id == saved }

/** Recently updated pages in [collectiveId], or across every collective when null. */
internal fun PageRepository.observeRecentlyUpdated(
    collectiveId: Long?,
    limit: Int,
): Flow<List<PageListItem>> =
    if (collectiveId == null) {
        observeRecentPagesAcrossCollectives(limit)
    } else {
        observeRecentPages(collectiveId, limit)
    }
