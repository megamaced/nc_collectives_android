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
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Renders [markdown] into an Android `TextView` via Markwon, themed against
 * the current M3 colour scheme. Direct Markwon (rather than the
 * compose-markdown wrapper) so we can override the code background and
 * text colours — the wrapper's defaults render unreadable text on the
 * app's dark scheme.
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

    val bodyTextSizeSp = MaterialTheme.typography.bodyLarge.fontSize
        .takeIf { it.type == TextUnitType.Sp }
        ?.value
        ?: 16f

    val markwon = remember(codeBg, codeFg, linkColor) {
        Markwon
            .builder(context)
            .usePlugin(LinkifyPlugin.create())
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
