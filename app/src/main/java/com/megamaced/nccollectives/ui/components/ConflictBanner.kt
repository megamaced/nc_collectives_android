package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.megamaced.nccollectives.R

/**
 * B-78: "Discard" destroys `draftBodyMd` — the only copy of edits that lost
 * an If-Match race — and it sits one button-width from "Replace page". It
 * gets a confirmation dialog, the same as every other destructive action in
 * the app (page trash, collective trash, attachment delete).
 */
@Composable
fun ConflictBanner(
    draft: String,
    onReplace: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.conflict_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = stringResource(R.string.conflict_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(draft)) }) { Text(stringResource(R.string.action_copy)) }
            TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.action_discard)) }
            TextButton(onClick = onReplace) { Text(stringResource(R.string.conflict_replace)) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.conflict_discard_title)) },
            text = {
                Text(stringResource(R.string.conflict_discard_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.conflict_keep_draft)) }
            },
        )
    }
}
