package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Minimal emoji picker: a grid of popular pages-relevant emojis plus a free
 * text field for anything else (Android's system keyboard handles long-tail
 * via long-press). A "Clear" button removes the emoji entirely.
 *
 * A categorised picker with search is intentionally out of scope here —
 * 99 % of pages get one of the 30-or-so glyphs below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var custom by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Set page emoji",
                style = MaterialTheme.typography.titleMedium,
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(POPULAR_EMOJIS) { emoji ->
                    EmojiCell(emoji = emoji, onClick = { onPick(emoji) })
                }
            }

            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.take(8) }, // emojis are 1–6 chars after composition
                label = { Text("Custom emoji") },
                placeholder = { Text("Paste or type any emoji") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onPick("") }) { Text("Clear") }
                TextButton(
                    enabled = custom.isNotBlank(),
                    onClick = { onPick(custom.trim()) },
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun EmojiCell(
    emoji: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleLarge)
    }
}

// Curated set — paper / book / pencil heavy, plus the obvious common ones.
private val POPULAR_EMOJIS = listOf(
    "📄",
    "📝",
    "📒",
    "📓",
    "📔",
    "📕",
    "📗",
    "📘",
    "📙",
    "📚",
    "✏️",
    "🖊️",
    "🖋️",
    "📌",
    "📎",
    "🔖",
    "💡",
    "💬",
    "✅",
    "❗",
    "❓",
    "⭐",
    "🔥",
    "🚀",
    "🎯",
    "🏗️",
    "🔧",
    "🔒",
    "🌐",
    "📊",
    "🤖",
    "🐰",
)
