package com.megamaced.nccollectives.ui.screen.page

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.ui.attachment.rememberCameraCapture
import com.megamaced.nccollectives.ui.attachment.uriDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentsScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: AttachmentsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val attachments by viewModel.attachments.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showUploadSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.statusMessage) {
        ui.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatus()
        }
    }

    val cameraCapture = rememberCameraCapture { uri, displayName ->
        viewModel.enqueueUpload(uri, displayName, "image/jpeg")
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // Persist read access across process death so the worker can
            // still open the URI when it runs later.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = uriDisplayName(context, uri) ?: "image.jpg"
            val type = context.contentResolver.getType(uri)
            viewModel.enqueueUpload(uri, name, type)
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Attachments", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showUploadSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            when {
                ui.isRefreshing && attachments.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                attachments.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No attachments yet. Tap Add to upload an image.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(attachments, key = { it.id }) { attachment ->
                            AttachmentTile(
                                attachment = attachment,
                                onDelete = { pendingDelete = attachment.fileName },
                            )
                        }
                    }
            }
        }
    }

    if (showUploadSheet) {
        UploadSourceSheet(
            onCamera = {
                showUploadSheet = false
                cameraCapture.launch()
            },
            onGallery = {
                showUploadSheet = false
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onDismiss = { showUploadSheet = false },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete attachment?") },
            text = { Text("\"$name\" will be removed from the page.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(name)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AttachmentTile(
    attachment: Attachment,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                attachment.status != Attachment.Status.REMOTE ->
                    CircularProgressIndicator(strokeWidth = 2.dp)
                attachment.isImage && attachment.remoteUrl != null ->
                    AsyncImage(
                        model = attachment.remoteUrl,
                        contentDescription = attachment.fileName,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp),
                    )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp, start = 4.dp),
                maxLines = 2,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSourceSheet(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Add attachment",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            SheetAction(
                icon = androidx.compose.material.icons.Icons.Filled.PhotoCamera,
                label = "Take photo",
                onClick = onCamera,
            )
            HorizontalDivider()
            SheetAction(
                icon = androidx.compose.material.icons.Icons.Filled.PhotoLibrary,
                label = "Pick from gallery",
                onClick = onGallery,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
