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
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListDrawable
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

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
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = LocalContentColor.current

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
            markwon.setMarkdown(tv, markdown)
        },
    )
}

// `TableTheme` is referenced via TablePlugin's builder lambda above and is
// otherwise unused at the file level — silence the unused-import warning.
@Suppress("unused")
private val tableThemeReference = TableTheme::class
