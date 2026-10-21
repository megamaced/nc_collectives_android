package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.megamaced.nccollectives.R

@Composable
fun RenameDialog(
    currentTitle: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.page_title_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (value.isNotBlank()) onRename(value.trim())
                }),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() && value.trim() != currentTitle,
                onClick = { onRename(value.trim()) },
            ) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
