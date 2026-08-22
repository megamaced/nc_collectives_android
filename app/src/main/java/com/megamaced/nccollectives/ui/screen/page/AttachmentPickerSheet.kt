package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.domain.model.Attachment

/**
 * Picker the editor opens when the user taps the attachment toolbar action.
 * Lists the page's existing attachments; tapping one returns its filename,
 * which the caller inserts at the cursor via
 * [MarkdownToolbarActions.insertAttachment] (embed syntax for images, a
 * plain link for everything else). Also offers Camera / Gallery / File
 * uploads — the upload runs in the background and the sheet stays open so
 * the user can pick it once the row turns REMOTE.
 *
 * B-73: the three upload sources arrive as callbacks rather than being
 * launched from here. Activity-result launchers only hold their
 * registration while composed, and this sheet is composed conditionally —
 * a capture in progress while the activity was recreated came back to a
 * closed sheet, so the result had nowhere to be delivered. `PageEditScreen`
 * owns the launchers, the same way `AttachmentsScreen` does.
 *
 * Reuses [AttachmentsViewModel] — both this sheet and the standalone
 * `AttachmentsScreen` resolve `pageId` from the same `{pageId}` path arg.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPickerSheet(
    onPick: (fileName: String) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: AttachmentsViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Insert attachment",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                UploadChip(
                    icon = Icons.Filled.PhotoCamera,
                    label = "Camera",
                    onClick = onCamera,
                    modifier = Modifier.weight(1f),
                )
                UploadChip(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Gallery",
                    onClick = onGallery,
                    modifier = Modifier.weight(1f),
                )
                UploadChip(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    label = "File",
                    onClick = onFile,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (attachments.isEmpty()) {
                Text(
                    text = "No attachments yet. Capture or pick one above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(attachments, key = { it.id }) { att ->
                        AttachmentRow(
                            attachment = att,
                            onClick = {
                                if (att.status == Attachment.Status.REMOTE) {
                                    onPick(att.fileName)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun UploadChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = attachment.status == Attachment.Status.REMOTE, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                attachment.status != Attachment.Status.REMOTE -> {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                }

                attachment.isImage && attachment.remoteUrl != null -> {
                    AsyncImage(
                        model = attachment.remoteUrl,
                        contentDescription = attachment.fileName,
                        modifier = Modifier.size(56.dp),
                    )
                }

                else -> {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                }
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = attachment.fileName, style = MaterialTheme.typography.bodyMedium)
            val statusLabel = when (attachment.status) {
                Attachment.Status.REMOTE -> "Tap to insert"
                Attachment.Status.PENDING -> "Waiting to upload"
                Attachment.Status.UPLOADING -> "Uploading…"
                Attachment.Status.FAILED -> "Upload failed"
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
