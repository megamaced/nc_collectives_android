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
    // B-34: `URLDecoder.decode` is form-decoding — it treats `+` as
    // space. [expandWikilinks] only percent-encodes spaces and leaves
    // literal `+` alone, so a wikilink target of `C++` previously
    // decoded back to `"C  "` (two spaces) and the resolver failed.
    // Pre-escape literal `+` to `%2B` so URLDecoder leaves it
    // verbatim. We keep URLDecoder over `Uri.decode` because the
    // tests run on the JVM (no Android framework on the test
    // classpath) and `Uri.decode` would throw `Method not mocked`.
    val preEscaped = raw.replace("+", "%2B")
    val decoded = runCatching { URLDecoder.decode(preEscaped, "UTF-8") }.getOrElse { raw }
    val withoutQueryAndFragment = decoded
        .removePrefix("./")
        .removePrefix("/")
        .substringBefore('#')
        .substringBefore('?')
    // R-32: case-insensitive `.md` strip. Previous double `removeSuffix`
    // only matched `.md` / `.MD` so `.Md` / `.mD` slipped through.
    val withoutMd = if (withoutQueryAndFragment.endsWith(".md", ignoreCase = true)) {
        withoutQueryAndFragment.dropLast(3)
    } else {
        withoutQueryAndFragment
    }
    return withoutMd.trim()
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
