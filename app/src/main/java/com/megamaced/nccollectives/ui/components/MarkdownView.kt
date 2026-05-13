package com.megamaced.nccollectives.ui.components

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.viewinterop.AndroidView
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
import io.noties.markwon.ext.tables.TableTheme
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
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = LocalContentColor.current
    val okHttpClient = remember {
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                MarkdownViewEntryPoint::class.java,
            ).okHttpClient()
    }
    val resolvedMarkdown = remember(markdown, imageBaseUrl) {
        if (imageBaseUrl.isNullOrEmpty()) markdown else absolutizeImageRefs(markdown, imageBaseUrl)
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
                        builder.linkResolver { _, link -> handleMarkdownLink(context, link) }
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
 * slash so it points at `imageBaseUrl/relativeRef`. Leaves absolute URLs
 * (`http`, `https`, `data:`, `file://`) untouched.
 */
internal fun absolutizeImageRefs(
    markdown: String,
    imageBaseUrl: String,
): String {
    val base = if (imageBaseUrl.endsWith('/')) imageBaseUrl else "$imageBaseUrl/"
    val pattern = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(\\s+[^)]*)?\\)")
    return pattern.replace(markdown) { match ->
        val alt = match.groupValues[1]
        val target = match.groupValues[2]
        val trailing = match.groupValues[3]
        val resolved = if (
            target.startsWith("http://", ignoreCase = true) ||
            target.startsWith("https://", ignoreCase = true) ||
            target.startsWith("data:", ignoreCase = true) ||
            target.startsWith("file://", ignoreCase = true) ||
            target.startsWith('/')
        ) {
            target
        } else {
            base + target
        }
        "![$alt]($resolved$trailing)"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MarkdownViewEntryPoint {
    fun okHttpClient(): OkHttpClient
}

// `TableTheme` is referenced via TablePlugin's builder lambda above and is
// otherwise unused at the file level — silence the unused-import warning.
@Suppress("unused")
private val tableThemeReference = TableTheme::class
