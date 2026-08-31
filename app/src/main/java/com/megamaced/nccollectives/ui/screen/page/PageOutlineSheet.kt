package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.ui.components.PageHeading

/**
 * The page index (issue #15), as a bottom sheet rather than the web app's
 * hover-out rail: there is no hover on a touch screen, and a permanently
 * docked rail would cost width a phone does not have.
 *
 * [onSelect] is called with null for "Top of page".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageOutlineSheet(
    headings: List<PageHeading>,
    onSelect: (PageHeading?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Indent relative to the shallowest heading present, so a document that
    // starts at `##` is not permanently pushed in by one step.
    val baseLevel = headings.minOfOrNull { it.level } ?: 1

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Page index", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    OutlineRow(
                        title = "Top of page",
                        indentLevel = 0,
                        emphasised = true,
                        onClick = { onSelect(null) },
                    )
                }
                // No `key`: heading text is not unique (two "Notes" sections
                // are ordinary) and the rendered char offset shifts under the
                // list on every re-render, so position is the only honest
                // identity here.
                items(headings) { heading ->
                    OutlineRow(
                        title = heading.title,
                        indentLevel = heading.level - baseLevel,
                        emphasised = false,
                        onClick = { onSelect(heading) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(
    title: String,
    indentLevel: Int,
    emphasised: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        style = if (emphasised) {
            MaterialTheme.typography.bodyLarge
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = if (indentLevel == 0) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                // Capped: a `######` under an `#` would otherwise indent the
                // label off a narrow screen.
                start = (indentLevel.coerceAtMost(4) * 16).dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
    )
}
