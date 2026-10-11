package com.megamaced.nccollectives.domain.model

import android.icu.text.CollationKey
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import java.util.Locale

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
 * Page titles and collective names both sort this way, so A–Z means the
 * same in the collective list as in a page tree.
 *
 * R-44: the sort key is built once per item per sort, never per
 * comparison. A [CollationKey] compares as bytes, so the sort itself
 * allocates nothing. The collator is created per sort because an unfrozen
 * one is not thread-safe, and these sorts run on whatever dispatcher a flow
 * is on.
 */
internal class Collated<T>(
    val item: T,
    val key: CollationKey,
)

/** [items], each with the collation key of its [text]. See [Collated]. */
internal fun <T> collated(
    items: List<T>,
    text: (T) -> String,
): List<Collated<T>> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    // ICU documents every instance getInstance hands out as rule-based. The
    // safe cast is so that, were that ever not so, titles would lose numeric
    // order rather than the list crash on a sort.
    (collator as? RuleBasedCollator)?.numericCollation = true
    return items.map { Collated(it, collator.getCollationKey(text(it))) }
}
