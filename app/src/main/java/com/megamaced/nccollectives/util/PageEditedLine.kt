package com.megamaced.nccollectives.util

/**
 * The subtitle under a page title in a list: who last edited it and when.
 *
 * Both halves are optional in practice. A page the server has never
 * reported an editor for still has a useful timestamp, and a row whose
 * [serverTimestampSeconds] is missing still has a useful name — so neither
 * absence is allowed to suppress the other. Null means there is genuinely
 * nothing to say, and the caller draws no line at all.
 *
 * Pure, and separate from the composable, because the wording is the part
 * worth pinning with tests and the unit-test source set is plain JVM.
 *
 * @param serverTimestampSeconds last-modified from the server, *seconds*
 *   since epoch — the unit every page model carries. 0 or negative means
 *   unknown.
 * @param nowMillis current time in milliseconds.
 */
fun pageEditedLine(
    lastUserDisplayName: String,
    serverTimestampSeconds: Long,
    nowMillis: Long,
): String? {
    val name = lastUserDisplayName.takeIf { it.isNotBlank() }
    val relative = serverTimestampSeconds
        .takeIf { it > 0L }
        ?.let { relativeTimeAgo(it * 1_000L, nowMillis) }
    return when {
        name != null && relative != null -> "Edited by $name · $relative"
        name != null -> "Edited by $name"
        relative != null -> "Edited $relative"
        else -> null
    }
}
