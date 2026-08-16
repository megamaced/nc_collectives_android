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
import com.megamaced.nccollectives.ui.theme.LocalTextScale
import com.megamaced.nccollectives.util.AttachmentRef
import com.megamaced.nccollectives.util.demoteNonImageEmbeds
import com.megamaced.nccollectives.util.expandWikilinks
import com.megamaced.nccollectives.util.handleMarkdownLink
import com.megamaced.nccollectives.util.isAttachmentDirSegment
import com.megamaced.nccollectives.util.pageDirectoryUrlFrom
import com.megamaced.nccollectives.util.rewriteCallouts
import com.megamaced.nccollectives.util.rewriteFootnotes
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
     * Page being rendered. Needed to resolve a bare-filename attachment
     * target (`[report.pdf](report.pdf)`) against the right
     * `.attachments.<pageId>` folder. When null, every scheme-less target is
     * treated as a wiki-page reference — the pre-attachment behaviour.
     */
    pageId: Long? = null,
    /**
     * Invoked when the user taps an in-app link — `[[Wiki]]` or a relative
     * markdown reference. The argument is the cleaned page title (URL-decoded,
     * `./` and `.md` stripped). Default ignores them.
     */
    onWikiLink: (String) -> Unit = {},
    /**
     * Invoked when the user taps a link pointing at one of the page's
     * attachments. Callers download the file and hand it to another app;
     * default ignores them (used by the editor's live preview, where a
     * tap-to-download would fight the edit session).
     */
    onAttachmentLink: (AttachmentRef) -> Unit = {},
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = LocalContentColor.current
    val onWikiLinkLatest by rememberUpdatedState(onWikiLink)
    val onAttachmentLinkLatest by rememberUpdatedState(onAttachmentLink)
    // Same reason as the callbacks: the Markwon instance is remembered on
    // `colorScheme` alone (R-25), so anything the link resolver closes over
    // has to be read through a latest-value holder or it would be pinned to
    // whatever it was when the theme last changed.
    val pageIdLatest by rememberUpdatedState(pageId)
    val okHttpClient = remember {
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                MarkdownViewEntryPoint::class.java,
            ).okHttpClient()
    }
    val resolvedMarkdown = remember(markdown, imageBaseUrl) {
        // Batch 31 + footnotes: rewrite Nextcloud Text dialect
        // extensions (footnotes, callouts, `==highlight==`) into shapes
        // Markwon already renders. Footnotes run first so highlight /
        // callout syntax inside a lifted footnote definition still gets
        // processed; wikilink expansion runs last. Each pass skips
        // fence/code regions independently.
        val withFootnotes = rewriteFootnotes(markdown)
        val withCallouts = rewriteCallouts(withFootnotes)
        val withHighlights = rewriteHighlights(withCallouts)
        val withWikiLinks = expandWikilinks(withHighlights)
        // Demote `![x](x.pdf)` to a link *before* absolutizing, so a
        // non-image attachment never acquires an image URL that
        // ImagesPlugin would try to decode as a bitmap.
        val withFileLinks = demoteNonImageEmbeds(withWikiLinks)
        if (imageBaseUrl.isNullOrEmpty()) {
            withFileLinks
        } else {
            absolutizeImageRefs(withFileLinks, imageBaseUrl)
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

    // Markwon sizes headings, code, and blockquotes as multipliers off the
    // TextView's own text size, so scaling this one value scales the whole
    // document proportionally.
    val bodyTextSizeSp = (
        MaterialTheme.typography.bodyLarge.fontSize
            .takeIf { it.type == TextUnitType.Sp }
            ?.value
            ?: 16f
    ) * LocalTextScale.current

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
                            handleMarkdownLink(
                                context = context,
                                url = link,
                                pageId = pageIdLatest,
                                onWikiLink = onWikiLinkLatest,
                                onAttachmentLink = onAttachmentLinkLatest,
                            )
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
                // Markwon leaves leading at the font's default, which
                // reads as a dense wall of text on a phone (issue #6).
                // Multiplier rather than `lineSpacingExtra` so the gap
                // grows with the text size instead of shrinking away
                // relative to it at the larger `TextScale` steps.
                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            }
        },
        update = { tv ->
            tv.setTextColor(bodyColor)
            // Set *before* `setMarkdown`: heading and code spans read the
            // paint's text size when they scale themselves. `AndroidView`
            // reuses the view across recompositions, so this has to live
            // here and not in `factory`, or a text-size change wouldn't
            // land until the view was rebuilt for some other reason.
            tv.textSize = bodyTextSizeSp
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
 *
 * A ref that already names an attachment directory
 * (`![x](.attachments.12/x.png)` — the shape Nextcloud Text writes) is
 * resolved against the *page* directory instead of [imageBaseUrl], which
 * already ends in `.attachments.<pageId>/`. Without that split the segment
 * would be doubled up (`…/.attachments.12/.attachments.12/x.png`) and the
 * image would 404.
 */
internal fun absolutizeImageRefs(
    markdown: String,
    imageBaseUrl: String,
): String {
    val base = if (imageBaseUrl.endsWith('/')) imageBaseUrl else "$imageBaseUrl/"
    val pageDirBase = pageDirectoryUrlFrom(base)
    return IMAGE_REF_PATTERN.replace(markdown) { match ->
        val image = match.groups["image"]
        if (image == null) {
            // Fence or inline code — emit verbatim.
            return@replace match.value
        }
        val alt = match.groups["alt"]?.value.orEmpty()
        val target = match.groups["target"]?.value.orEmpty()
        val trailing = match.groups["trailing"]?.value.orEmpty()
        val namesAttachmentDir = target
            .split('/')
            .any { isAttachmentDirSegment(it) }
        val resolved = when {
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.startsWith('/') -> target
            // Already directory-qualified — join against the page's own
            // directory. Falls back to `base` if the attachments URL
            // wasn't the expected shape, i.e. never worse than before.
            namesAttachmentDir && pageDirBase != null ->
                pageDirBase + target.removePrefix("./")
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

/**
 * Leading for rendered page bodies, as a multiple of the font's own line
 * height (≈1.2× the text size for the default family). 1.2 therefore puts
 * a rendered page near the 1.5 line-height-to-size ratio M3 specifies for
 * `bodyLarge`, i.e. level with every Compose `Text` in the app instead of
 * at the tighter TextView default.
 *
 * Deliberately not tied to `TextScale`: "too small" and "too dense" are
 * separate complaints in issue #6, and this one has a right answer that
 * doesn't depend on the chosen size.
 */
private const val LINE_SPACING_MULTIPLIER = 1.2f

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MarkdownViewEntryPoint {
    fun okHttpClient(): OkHttpClient
}
