package com.megamaced.nccollectives.ui.components

import android.text.method.LinkMovementMethod
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
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import io.noties.markwon.linkify.LinkifyPlugin
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
        val withWikiLinks = expandWikilinks(markdown)
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

    val bodyTextSizeSp = MaterialTheme.typography.bodyLarge.fontSize
        .takeIf { it.type == TextUnitType.Sp }
        ?.value
        ?: 16f

    val markwon = remember(
        codeBg,
        codeFg,
        linkColor,
        outline,
        tableHeaderRow,
        tableOddRow,
        tableEvenRow,
        taskBoxChecked,
        taskBoxUnchecked,
    ) {
        Markwon
            .builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
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
            ).usePlugin(
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
