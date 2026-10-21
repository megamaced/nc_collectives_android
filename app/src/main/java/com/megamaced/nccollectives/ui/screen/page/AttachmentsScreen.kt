package com.megamaced.nccollectives.ui.screen.page

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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.ui.attachment.openAttachmentExternally
import com.megamaced.nccollectives.ui.attachment.rememberCameraCapture
import com.megamaced.nccollectives.ui.attachment.uriDisplayName
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect
import com.megamaced.nccollectives.util.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentsScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: AttachmentsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showUploadSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    SnackbarStatusEffect(ui.statusMessage, snackbarHostState, viewModel::dismissStatus)

    val cameraCapture = rememberCameraCapture { uri, displayName ->
        viewModel.enqueueUpload(uri, displayName, "image/jpeg")
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // B-29: no `takePersistableUriPermission` — photo-picker URIs
            // aren't persistable and we copy the bytes into our own cache
            // in AttachmentRepositoryImpl.enqueueUpload anyway.
            val name = uriDisplayName(context, uri) ?: "image.jpg"
            val type = context.contentResolver.getType(uri)
            viewModel.enqueueUpload(uri, name, type)
        }
    }
    // `OpenDocument` rather than `GetContent`: the document picker reaches
    // every provider (Drive, Files, SD card) and returns a stable URI. The
    // visual-media picker above can only ever return images and video, which
    // is why a PDF or spreadsheet couldn't be attached from the app at all
    // before this launcher existed.
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uriDisplayName(context, uri) ?: "attachment"
            val type = context.contentResolver.getType(uri)
            viewModel.enqueueUpload(uri, name, type)
        }
    }

    // Attachment staged — hand it to whichever app the user has for the type.
    LaunchedEffect(ui.attachmentToOpen) {
        val attachment = ui.attachmentToOpen ?: return@LaunchedEffect
        val opened = openAttachmentExternally(context, attachment)
        viewModel.acknowledgeOpened(
            failureMessage = if (opened) null else uiText(R.string.attachment_no_app, attachment.fileName),
        )
    }

    val resources by rememberUpdatedState(LocalResources.current)
    LaunchedEffect(ui.downloadingAttachment) {
        val name = ui.downloadingAttachment ?: return@LaunchedEffect
        // Indefinite: implicitly dismissed when the download finishes and
        // the state change cancels this effect.
        snackbarHostState.showSnackbar(
            message = resources.getString(R.string.attachment_downloading, name),
            duration = SnackbarDuration.Indefinite,
        )
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.attachments_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            when {
                ui.isRefreshing && attachments.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                attachments.isEmpty() -> {
                    // `EmptyState` rather than a centred `Text`: it carries the
                    // `verticalScroll` that hands an unconsumed drag up to the
                    // `PullToRefreshBox`, so the pull works on the empty list
                    // too — which is the one you most want to retry.
                    EmptyState(
                        title = stringResource(R.string.attachments_empty_title),
                        message = stringResource(R.string.attachments_empty_message),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(attachments, key = { it.id }) { attachment ->
                            AttachmentTile(
                                attachment = attachment,
                                onOpen = { viewModel.open(attachment.fileName) },
                                onDelete = { pendingDelete = attachment.fileName },
                                onRetry = { viewModel.retryUpload(attachment.fileName) },
                            )
                        }
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
            onFile = {
                showUploadSheet = false
                fileLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showUploadSheet = false },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.attachment_delete_title)) },
            text = { Text(stringResource(R.string.attachment_delete_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AttachmentTile(
    attachment: Attachment,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val isOpenable = attachment.status == Attachment.Status.REMOTE
    val hasFailed = attachment.status == Attachment.Status.FAILED
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // The preview area is the open affordance; Delete keeps its
                // own button so the two don't share a gesture. After
                // `background` so the ripple draws on top of it rather than
                // under. `role` per the Batch 19 accessibility pass — a
                // clickable Box announces as nothing without it.
                .clickable(
                    enabled = isOpenable,
                    onClick = onOpen,
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Issue #23: a failed upload used to render as a progress
                // spinner, forever — the grid drew one for every non-REMOTE
                // status, and nothing ever moved a FAILED row out of it. It
                // reads as an error and offers Retry now.
                hasFailed -> {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = stringResource(R.string.attachment_status_failed),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                attachment.status != Attachment.Status.REMOTE -> {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }

                attachment.isImage && attachment.remoteUrl != null -> {
                    AsyncImage(
                        model = attachment.remoteUrl,
                        contentDescription = attachment.fileName,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp),
                    )
                }
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
            if (hasFailed) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.attachment_retry_upload, attachment.fileName))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSourceSheet(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
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
                text = stringResource(R.string.attachment_add_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            SheetAction(
                icon = androidx.compose.material.icons.Icons.Filled.PhotoCamera,
                label = stringResource(R.string.attachment_take_photo),
                onClick = onCamera,
            )
            SheetAction(
                icon = androidx.compose.material.icons.Icons.Filled.PhotoLibrary,
                label = stringResource(R.string.attachment_pick_gallery),
                onClick = onGallery,
            )
            SheetAction(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                label = stringResource(R.string.attachment_pick_file),
                onClick = onFile,
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
