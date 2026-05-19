package com.megamaced.nccollectives.ui.components

import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.viewinterop.AndroidView
import com.megamaced.nccollectives.util.expandWikilinks
import com.megamaced.nccollectives.util.handleMarkdownLink
import com.megamaced.nccollectives.util.rewriteCallouts
import com.megamaced.nccollectives.util.rewriteHighlights
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListDrawable
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jThemeM3
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import okhttp3.OkHttpClient

/**
 * Renders [markdown] into an Android `TextView` via Markwon, themed against
 * the current M3 colour scheme. Direct Markwon (rather than a generic
 * wrapper) so we can override code-block, table, and task-list colours
 * — the defaults render unreadable text on the app's dark scheme.
 *
 * Supports GFM tables, task lists, strikethrough, and inline link
 * autolinking via Markwon's ext-* plugins.
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    /**
     * Base URL appended to relative image references (`![alt](filename)`).
     * Pages with a known attachments directory pass its WebDAV URL here so
     * inline images render against the authenticated Nextcloud host. Other
     * relative links fall through unchanged.
     */
    imageBaseUrl: String? = null,
    /**
     * Invoked when the user taps an in-app link — `[[Wiki]]` or a relative
     * markdown reference. The argument is the cleaned page title (URL-decoded,
     * `./` and `.md` stripped). Default ignores them.
     */
    onWikiLink: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = LocalContentColor.current
    val onWikiLinkLatest by rememberUpdatedState(onWikiLink)
    val okHttpClient = remember {
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                MarkdownViewEntryPoint::class.java,
            ).okHttpClient()
    }
    val resolvedMarkdown = remember(markdown, imageBaseUrl) {
        // Batch 31: rewrite Nextcloud Text dialect extensions
        // (callouts, `==highlight==`) into shapes Markwon already
        // renders. Done before wikilink expansion so the alternation
        // patterns don't fight over the same fence/code regions.
        val withCallouts = rewriteCallouts(markdown)
        val withHighlights = rewriteHighlights(withCallouts)
        val withWikiLinks = expandWikilinks(withHighlights)
        if (imageBaseUrl.isNullOrEmpty()) {
            withWikiLinks
        } else {
            absolutizeImageRefs(withWikiLinks, imageBaseUrl)
        }
    }

    val bodyColor = contentColor.toArgb()
    val codeBg = colorScheme.surfaceContainerHigh.toArgb()
    val codeFg = colorScheme.onSurface.toArgb()
    val linkColor = colorScheme.primary.toArgb()
    val outline = colorScheme.outline.toArgb()
    val tableHeaderRow = colorScheme.surfaceContainerHighest.toArgb()
    val tableOddRow = colorScheme.surface.toArgb()
    val tableEvenRow = colorScheme.surfaceContainer.toArgb()
    val taskBoxChecked = colorScheme.primary.toArgb()
    val taskBoxUnchecked = colorScheme.onSurfaceVariant.toArgb()
    val prismKeyword = colorScheme.primary.toArgb()
    val prismString = colorScheme.tertiary.toArgb()
    val prismLiteral = colorScheme.secondary.toArgb()
    val prismComment = colorScheme.outline.toArgb()
    val prismFunction = colorScheme.primary.toArgb()
    val prismOperator = colorScheme.onSurfaceVariant.toArgb()

    val bodyTextSizeSp = MaterialTheme.typography.bodyLarge.fontSize
        .takeIf { it.type == TextUnitType.Sp }
        ?.value
        ?: 16f

    // R-25: every colour derives from `colorScheme`; a single ColorScheme
    // reference change (theme switch) is the only event that needs to
    // rebuild Markwon. Spelling out 14+ individual ARGB ints as remember
    // keys forced Compose to compare them all each recomposition, with
    // no behavioural difference from keying on the `colorScheme` itself.
    val markwon = remember(colorScheme) {
        val prism4j = Prism4j(
            com.megamaced.nccollectives.util
                .CollectivesGrammarLocator(),
        )
        val prismTheme = Prism4jThemeM3(
            codeBg,
            codeFg,
            prismKeyword,
            prismString,
            prismComment,
            prismLiteral,
            prismFunction,
            prismOperator,
        )
        Markwon
            .builder(context)
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
            .usePlugin(StrikethroughPlugin.create())
            // HtmlPlugin (Batch 24) renders `<br>`, `<sub>`, `<sup>`,
            // `<a>`, `<img>`, alignment and other inline HTML that
            // Markdown leaves through to the rendered Spannable. Note:
            // `<details>`/`<summary>` aren't interactive — Markwon
            // doesn't ship a collapsible widget, so they render inline.
            .usePlugin(HtmlPlugin.create())
            .usePlugin(
                ImagesPlugin.create { plugin ->
                    plugin.addSchemeHandler(OkHttpNetworkSchemeHandler.create(okHttpClient))
                },
            ).usePlugin(
                TablePlugin.create { builder ->
                    builder
                        .tableBorderColor(outline)
                        .tableHeaderRowBackgroundColor(tableHeaderRow)
                        .tableOddRowBackgroundColor(tableOddRow)
                        .tableEvenRowBackgroundColor(tableEvenRow)
                },
            ).usePlugin(
                TaskListPlugin.create(
                    TaskListDrawable(
                        taskBoxUnchecked,
                        taskBoxChecked,
                        // Checkmark inside the checked box.
                        bodyColor,
                    ),
                ),
            ).usePlugin(SyntaxHighlightPlugin.create(prism4j, prismTheme))
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .codeBackgroundColor(codeBg)
                            .codeTextColor(codeFg)
                            .codeBlockBackgroundColor(codeBg)
                            .codeBlockTextColor(codeFg)
                            .linkColor(linkColor)
                    }

                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver { _, link ->
                            handleMarkdownLink(context, link, onWikiLinkLatest)
                        }
                    }
                },
            ).build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(bodyColor)
                textSize = bodyTextSizeSp
            }
        },
        update = { tv ->
            tv.setTextColor(bodyColor)
            markwon.setMarkdown(tv, resolvedMarkdown)
        },
    )
}

/**
 * Rewrites every `![alt](relativeRef)` whose URL has no scheme + no leading
 * slash so it points at `imageBaseUrl/relativeRef`. Leaves `http(s)://`
 * URLs untouched; **drops** `data:`, `file://`, and other schemes by
 * resolving them as relative (S-8). Image references inside fenced code
 * blocks or inline code spans are left alone (B-4).
 */
internal fun absolutizeImageRefs(
    markdown: String,
    imageBaseUrl: String,
): String {
    val base = if (imageBaseUrl.endsWith('/')) imageBaseUrl else "$imageBaseUrl/"
    return IMAGE_REF_PATTERN.replace(markdown) { match ->
        val image = match.groups["image"]
        if (image == null) {
            // Fence or inline code — emit verbatim.
            return@replace match.value
        }
        val alt = match.groups["alt"]?.value.orEmpty()
        val target = match.groups["target"]?.value.orEmpty()
        val trailing = match.groups["trailing"]?.value.orEmpty()
        val resolved = when {
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.startsWith('/') -> target
            // `data:`, `file://`, custom schemes — treat as relative so
            // they go through the authenticated OkHttp scheme handler,
            // which only knows about http(s) and will fail loudly.
            else -> base + target.substringAfter("://")
        }
        "![$alt]($resolved$trailing)"
    }
}

// Same alternation strategy as `WIKILINK_PATTERN` in `MarkdownLinkResolver.kt`:
// fenced code → inline code → image ref. Earlier alternations win, so refs
// inside code segments are consumed by the code groups first.
private val IMAGE_REF_PATTERN = Regex(
    "(?s)" +
        "(?<fence>```.*?```|~~~.*?~~~)" +
        "|(?<code>`[^`\\n]+`)" +
        "|(?<image>!\\[(?<alt>[^\\]]*)]\\((?<target>[^)\\s]+)(?<trailing>\\s+[^)]*)?\\))",
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MarkdownViewEntryPoint {
    fun okHttpClient(): OkHttpClient
}
