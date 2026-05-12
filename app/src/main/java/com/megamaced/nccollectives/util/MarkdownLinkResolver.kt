package com.megamaced.nccollectives.util

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import timber.log.Timber

/**
 * Decides what to do when the user taps a link inside a rendered markdown page.
 *
 * For now this only handles `http(s)` links — those open in Chrome Custom Tabs.
 * In-app navigation for wiki-style `[[Page Name]]` links and relative `.md`
 * targets is intentionally a no-op (logged at debug level) and will be wired
 * up alongside the page-title index in a later batch.
 */
fun handleMarkdownLink(
    context: Context,
    url: String,
) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    if (uri == null) {
        Timber.d("Ignored unparseable markdown link: %s", url)
        return
    }
    val scheme = uri.scheme?.lowercase()
    when (scheme) {
        "http", "https" -> CustomTabsIntent.Builder().build().launchUrl(context, uri)
        else -> Timber.d("Ignored markdown link with scheme=%s (in-app nav TBD)", scheme)
    }
}
