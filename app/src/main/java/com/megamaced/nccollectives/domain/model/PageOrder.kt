package com.megamaced.nccollectives.domain.model

/**
 * How a collective's pages are ordered among their siblings — the web app's
 * per-user "Sort order" menu above the page list. The server keeps it per
 * user *and* per collective (`userSettings/pageOrder`, echoed back as
 * `userPageOrder` on each collective), so choosing an order here changes it
 * in the browser too, and the other way round.
 *
 * Declared in the order the app's sort menu lists them: the default, then
 * the two title orders, then the two time orders. Newest and oldest mean
 * the page's last change.
 *
 * [wire] is the integer the server stores. Upstream names the two time
 * orders backwards: its `byTimeAsc` (1) puts the most recently changed page
 * *first* (`b.timestamp - a.timestamp` in `src/util/sortOrders.js`), and
 * its UI labels it "Sort recently changed first". The names here describe
 * what the order does, and the wire values follow upstream's numbering.
 */
enum class PageOrder(
    val wire: Int,
) {
    /** Each parent's `subpageOrder` (what drag-to-reorder writes), then title for any page it doesn't list. */
    Custom(0),
    TitleAscending(2),
    TitleDescending(4),
    NewestFirst(1),
    OldestFirst(3),
    ;

    companion object {
        /**
         * An unrecognised value — a newer server's order — reads as
         * [Custom], which is also the server's own default.
         */
        fun fromWire(value: Int): PageOrder = entries.firstOrNull { it.wire == value } ?: Custom
    }
}

private fun List<PageListItem>.sortedVia(comparator: Comparator<Collated<PageListItem>>): List<PageListItem> =
    collated(this) { it.title }.sortedWith(comparator).map { it.item }

private val BY_TITLE: Comparator<Collated<PageListItem>> = compareBy { it.key }

private val BY_TITLE_DESCENDING: Comparator<Collated<PageListItem>> = BY_TITLE.reversed()

// Ties on the timestamp fall back to title, so pages saved in the same second
// keep a stable place instead of swapping on every emission.
private val BY_NEWEST_FIRST: Comparator<Collated<PageListItem>> =
    compareByDescending<Collated<PageListItem>> { it.item.serverTimestamp }.then(BY_TITLE)

private val BY_OLDEST_FIRST: Comparator<Collated<PageListItem>> =
    compareBy<Collated<PageListItem>> { it.item.serverTimestamp }.then(BY_TITLE)

/**
 * [pages] in title order — see [Collated]. Used for the tree's custom-order
 * fallback, as upstream's `byOrder` does, and for the new-page parent
 * picker.
 *
 * Deliberately *not* left to the DAO's `ORDER BY title COLLATE NOCASE`,
 * which folds ASCII case only and knows nothing of numbers. The tree builder is
 * also a pure function that must not assume its input arrived pre-sorted.
 */
internal fun sortedByTitle(pages: List<PageListItem>): List<PageListItem> = if (pages.size < 2) pages else pages.sortedVia(BY_TITLE)

/**
 * Orders one parent's children for display.
 *
 * [customOrder] is the parent's `subpageOrder`, and only [PageOrder.Custom]
 * reads it: ids it lists come first in that order, and everything else
 * follows in title order. The other orders ignore it, as upstream does —
 * one order applies at every level of the tree
 * (`sortedSubpagesForCollective` in `src/stores/pages.js`).
 *
 * `customOrder` is server-supplied and can repeat an id (issue #2), so it
 * is de-duplicated here; a repeated id must never produce a repeated row.
 */
internal fun orderSiblings(
    children: List<PageListItem>,
    customOrder: List<Long>,
    order: PageOrder,
): List<PageListItem> {
    if (children.size < 2) return children
    return when (order) {
        PageOrder.Custom -> inCustomOrder(children, customOrder)
        PageOrder.TitleAscending -> children.sortedVia(BY_TITLE)
        PageOrder.TitleDescending -> children.sortedVia(BY_TITLE_DESCENDING)
        PageOrder.NewestFirst -> children.sortedVia(BY_NEWEST_FIRST)
        PageOrder.OldestFirst -> children.sortedVia(BY_OLDEST_FIRST)
    }
}

private fun inCustomOrder(
    children: List<PageListItem>,
    customOrder: List<Long>,
): List<PageListItem> {
    val orderHint = customOrder.distinct()
    if (orderHint.isEmpty()) return sortedByTitle(children)
    val byChildId = children.associateBy { it.id }
    val hinted = orderHint.mapNotNull { byChildId[it] }
    val hintedIds = hinted.mapTo(HashSet()) { it.id }
    val rest = sortedByTitle(children.filter { it.id !in hintedIds })
    return hinted + rest
}
