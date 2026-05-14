package com.megamaced.nccollectives.util

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import timber.log.Timber
import java.net.URLDecoder

/**
 * Decides what to do when the user taps a link inside a rendered markdown
 * page. External `http(s)` links open in Chrome Custom Tabs; everything
 * else is forwarded to [onWikiLink] as a decoded page title so the caller
 * can resolve it against the cached page-title index and navigate in-app.
 *
 * Wikilinks reach this function as `[[Page Name]]` after [expandWikilinks]
 * rewrites them into `[Page Name](Page Name)`. Relative markdown references
 * like `[See](./Other%20Page)` also land here — leading `./`, trailing
 * `.md`, and URL-encoding are stripped before the callback fires.
 */
fun handleMarkdownLink(
    context: Context,
    url: String,
    onWikiLink: (String) -> Unit,
) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    when (scheme) {
        "http", "https" -> {
            checkNotNull(uri)
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }
        null -> onWikiLink(decodeWikiTarget(url))
        else -> Timber.d("Ignored markdown link with scheme=%s", scheme)
    }
}

internal fun decodeWikiTarget(raw: String): String {
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrElse { raw }
    return decoded
        .removePrefix("./")
        .removePrefix("/")
        .substringBefore('#')
        .substringBefore('?')
        .removeSuffix(".md")
        .removeSuffix(".MD")
        .trim()
}

/**
 * Rewrites every `[[Page Name]]` occurrence in [markdown] into a regular
 * markdown link `[Page Name](Page%20Name)` so Markwon renders it as
 * tappable. Pipe-aliased forms (`[[Target|Alias]]`) keep the alias as the
 * visible label. Escaped openers `\[[` are left alone.
 */
internal fun expandWikilinks(markdown: String): String {
    val pattern = Regex("(?<!\\\\)\\[\\[([^\\[\\]\\n]+?)]]")
    return pattern.replace(markdown) { match ->
        val raw = match.groupValues[1]
        val pipe = raw.indexOf('|')
        val target = if (pipe >= 0) raw.substring(0, pipe).trim() else raw.trim()
        val label = if (pipe >= 0) raw.substring(pipe + 1).trim() else target
        // Percent-encode spaces so Markwon picks up the link correctly; the
        // resolver decodes it back before lookup.
        val encoded = target.replace(" ", "%20")
        "[$label]($encoded)"
    }
}
