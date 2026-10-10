package com.megamaced.nccollectives.domain.model

import android.icu.text.CollationKey
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import java.util.Locale

/**
 * How a collective's pages are ordered among their siblings — the web app's
 * per-user "Sort order" menu above the page list. The server keeps it per
 * user *and* per collective (`userSettings/pageOrder`, echoed back as
 * `userPageOrder` on each collective), so choosing an order here changes it
 * in the browser too, and the other way round.
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
    RecentlyChangedFirst(1),
    TitleAscending(2),
    LeastRecentlyChangedFirst(3),
    TitleDescending(4),
    ;

    companion object {
        /**
         * An unrecognised value — a newer server's order — reads as
         * [Custom], which is also the server's own default.
         */
        fun fromWire(value: Int): PageOrder = entries.firstOrNull { it.wire == value } ?: Custom
    }
}

/**
 * Title order as the web app sorts titles: `localeCompare(…, { numeric:
 * true })` in upstream's `src/util/sortOrders.js`. ICU is what implements
 * that in the browser, and Android ships the same library, so this is the
 * same comparison rather than an imitation of it:
 *
 *  - **Numbers by value** — `numericCollation`. "Page 2" before "Page 10",
 *    for any Unicode decimal digits, so full-width "Page ２" and
 *    Arabic-Indic "٢" sort by value too.
 *  - **Language rules for letters** — a collator for the device locale, so
 *    "Éclair" sorts beside "eclair" rather than after "Zebra", and titles
 *    differing only by case get a fixed place (lower case first in most
 *    languages) rather than one that depends on the input order. Upstream
 *    uses the Nextcloud user's language; the device's is the nearest the
 *    app has.
 *
 * R-44: the sort key is built once per title per sort, never per
 * comparison. A [CollationKey] compares as bytes, so the sort itself
 * allocates nothing. The collator is created per sort because an unfrozen
 * one is not thread-safe, and these sorts run on whatever dispatcher a flow
 * is on.
 */
private class Keyed(
    val page: PageListItem,
    val title: CollationKey,
)

private fun keyed(pages: List<PageListItem>): List<Keyed> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    // ICU documents every instance getInstance hands out as rule-based. The
    // safe cast is so that, were that ever not so, titles would lose numeric
    // order rather than the tree crash on a sort.
    (collator as? RuleBasedCollator)?.numericCollation = true
    return pages.map { Keyed(it, collator.getCollationKey(it.title)) }
}

private fun List<PageListItem>.sortedVia(comparator: Comparator<Keyed>): List<PageListItem> =
    keyed(this).sortedWith(comparator).map { it.page }

private val BY_TITLE: Comparator<Keyed> = compareBy { it.title }

private val BY_TITLE_DESCENDING: Comparator<Keyed> = BY_TITLE.reversed()

// Ties on the timestamp fall back to title, so pages saved in the same second
// keep a stable place instead of swapping on every emission.
private val BY_RECENTLY_CHANGED_FIRST: Comparator<Keyed> =
    compareByDescending<Keyed> { it.page.serverTimestamp }.then(BY_TITLE)

private val BY_LEAST_RECENTLY_CHANGED_FIRST: Comparator<Keyed> =
    compareBy<Keyed> { it.page.serverTimestamp }.then(BY_TITLE)

/**
 * [pages] in title order — see [Keyed]. Used for the tree's custom-order
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
        PageOrder.RecentlyChangedFirst -> children.sortedVia(BY_RECENTLY_CHANGED_FIRST)
        PageOrder.LeastRecentlyChangedFirst -> children.sortedVia(BY_LEAST_RECENTLY_CHANGED_FIRST)
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
