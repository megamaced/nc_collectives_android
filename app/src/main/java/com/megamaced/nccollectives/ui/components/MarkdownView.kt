package com.megamaced.nccollectives.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.megamaced.nccollectives.util.handleMarkdownLink
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MarkdownText(
        markdown = markdown,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = LocalContentColor.current,
            textAlign = TextAlign.Start,
        ),
        onLinkClicked = { url -> handleMarkdownLink(context, url) },
    )
}
