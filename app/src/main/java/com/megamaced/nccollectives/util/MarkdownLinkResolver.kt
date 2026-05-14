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

// Combined pattern for [expandWikilinks]. Alternation order matters:
//   - fenced code blocks (``` ... ``` or ~~~ ... ~~~) on group `fence`,
//   - inline code spans (`...`) on group `code`,
//   - wikilinks `[[Target]]` or `[[Target|Alias]]` on group `wiki`.
//
// Earlier alternations win, so a `[[...]]` *inside* a fenced block or
// inline span is consumed by the code group first and falls through
// untouched. Closes B-4 (raw-string regex was mangling code samples).
//
// Caveat: indented code blocks (4+ spaces at line start) aren't tracked
// — they're rare in practice and mis-rewriting one is annoying, not
// destructive. The AST parser route would catch them but the additional
// complexity isn't worth it until someone reports it.
private val WIKILINK_PATTERN = Regex(
    "(?s)" +
        "(?<fence>```.*?```|~~~.*?~~~)" +
        "|(?<code>`[^`\\n]+`)" +
        "|(?<wiki>(?<!\\\\)\\[\\[(?<target>[^\\[\\]\\n|]+?)(?:\\|(?<alias>[^\\[\\]\\n]+?))?]])",
)

/**
 * Rewrites every `[[Page Name]]` occurrence in [markdown] into a regular
 * markdown link `[Page Name](Page%20Name)` so Markwon renders it as
 * tappable. Pipe-aliased forms (`[[Target|Alias]]`) keep the alias as the
 * visible label. Escaped openers `\[[` are left alone. Wikilinks inside
 * fenced code blocks or inline code spans are left unchanged.
 */
internal fun expandWikilinks(markdown: String): String =
    WIKILINK_PATTERN.replace(markdown) { match ->
        val wiki = match.groups["wiki"]
        if (wiki == null) {
            // Fence or inline code — emit verbatim.
            match.value
        } else {
            val target = match.groups["target"]
                ?.value
                ?.trim()
                .orEmpty()
            val alias = match.groups["alias"]?.value?.trim()
            val label = alias?.takeIf { it.isNotEmpty() } ?: target
            // Percent-encode spaces so Markwon picks up the link correctly; the
            // resolver decodes it back before lookup.
            val encoded = target.replace(" ", "%20")
            "[$label]($encoded)"
        }
    }
