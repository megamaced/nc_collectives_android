package com.megamaced.nccollectives.domain.model

/**
 * How a collective's pages open for its members, as set on the web: for
 * reading, or straight into the editor.
 */
enum class CollectivePageMode(
    val wire: Int,
) {
    View(0),
    Edit(1),
    ;

    companion object {
        /**
         * Only 1 is [Edit]. Anything else — 0, the server's default, or a
         * value this build doesn't know — reads as [View].
         */
        fun fromWire(value: Int): CollectivePageMode = if (value == Edit.wire) Edit else View
    }
}
