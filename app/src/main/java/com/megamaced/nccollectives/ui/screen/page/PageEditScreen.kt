package com.megamaced.nccollectives.ui.screen.page

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.ui.attachment.rememberCameraCapture
import com.megamaced.nccollectives.ui.attachment.uriDisplayName
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownView
import com.megamaced.nccollectives.ui.theme.LocalTextScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageEditScreen(
    innerPadding: PaddingValues,
    onClose: () -> Unit,
    viewModel: PageEditViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val draftBody by viewModel.draftBody.collectAsStateWithLifecycle()
    val imageBaseUrl by viewModel.imageBaseUrl.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Same preference the rendered page and the collaborative editor
    // read, so switching between edit, preview, and view doesn't change
    // the size of the text under the cursor. `lineHeight` scales with it
    // — leaving it fixed would crush the lines together at the larger
    // steps, which is the other half of what issue #6 reported.
    val textScale = LocalTextScale.current
    val bodyStyle = MaterialTheme.typography.bodyLarge.let { style ->
        style.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = style.fontSize * textScale,
            lineHeight = style.lineHeight * textScale,
        )
    }

    // The caret is a view concern, so the `TextFieldValue` lives here — but
    // the text inside it is only a cache of the ViewModel's draft (B-71).
    // `TextFieldValue.Saver` carries the caret through a configuration
    // change; after process death the draft comes back from the ViewModel and
    // the effect below reconciles the two.
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draftBody))
    }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var showDiscardPrompt by rememberSaveable { mutableStateOf(false) }
    // B-73: `rememberSaveable` — the activity is routinely destroyed while
    // the camera app is in front, and a plain `remember` brought the sheet
    // back closed with a capture still in flight.
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(draftBody) {
        if (fieldValue.text != draftBody) {
            // The ViewModel wins: either the seeding pass has just run, or
            // this is a fresh composable whose restored caret was measured
            // against an older text. Keep the caret where it was when it
            // still fits inside the draft.
            fieldValue = TextFieldValue(
                text = draftBody,
                selection = TextRange(fieldValue.selection.start.coerceIn(0, draftBody.length)),
            )
        }
    }

    // Every edit goes through here so the ViewModel's draft — the copy that
    // survives the activity — never lags behind what's on screen.
    val updateField: (TextFieldValue) -> Unit = { next ->
        fieldValue = next
        viewModel.onBodyChanged(next.text)
    }

    LaunchedEffect(ui.saveSucceeded) {
        if (!ui.saveSucceeded) return@LaunchedEffect
        // B-79: the conflict outcome still counts as saved (the edit is kept
        // as a draft on the page) but the user has to be told, and this
        // screen's snackbar host dies with the screen — so hold the close
        // until the notice has actually been on screen.
        ui.saveNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Long)
        }
        onClose()
    }

    LaunchedEffect(ui.saveError) {
        ui.saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val hasUnsavedChanges = ui.initialBody != null && draftBody != ui.initialBody
    val tryClose: () -> Unit = {
        if (hasUnsavedChanges) showDiscardPrompt = true else onClose()
    }

    BackHandler(enabled = true, onBack = tryClose)

    // B-73: the upload launchers are registered at screen level, not inside
    // the picker sheet. `rememberLauncherForActivityResult` holds its
    // registration only while it is composed; the system kills the activity
    // freely while the camera app is foregrounded, and the conditionally
    // composed sheet meant nothing re-registered the key on the way back —
    // the pending `TakePicture` result was delivered to nobody and the photo
    // was lost, `rememberSaveable` in `rememberCameraCapture` (B-31)
    // notwithstanding, because the composable holding it no longer existed.
    // `AttachmentsScreen` has always hoisted these; the two paths now match.
    val cameraCapture = rememberCameraCapture { uri, displayName ->
        viewModel.enqueueAttachment(uri, displayName, "image/jpeg")
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // B-29: no `takePersistableUriPermission` — photo-picker URIs
            // aren't persistable and we copy the bytes into our own cache
            // in AttachmentRepositoryImpl.enqueueUpload anyway.
            val name = uriDisplayName(context, uri) ?: "image.jpg"
            viewModel.enqueueAttachment(uri, name, context.contentResolver.getType(uri))
        }
    }
    // `OpenDocument` reaches every document provider, not just the media
    // store — the only way to attach a PDF / spreadsheet from the editor.
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uriDisplayName(context, uri) ?: "attachment"
            viewModel.enqueueAttachment(uri, name, context.contentResolver.getType(uri))
        }
    }

    if (showAttachmentPicker) {
        AttachmentPickerSheet(
            onPick = { fileName ->
                updateField(MarkdownToolbarActions.insertAttachment(fieldValue, fileName))
                showAttachmentPicker = false
            },
            onCamera = { cameraCapture.launch() },
            onGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onFile = { fileLauncher.launch(arrayOf("*/*")) },
            onDismiss = { showAttachmentPicker = false },
        )
    }

    if (showDiscardPrompt) {
        AlertDialog(
            onDismissRequest = { showDiscardPrompt = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved edits to this page.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardPrompt = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardPrompt = false }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        // The window is edge-to-edge (`enableEdgeToEdge()` in MainActivity),
        // so the manifest's `adjustResize` never fires and the soft keyboard
        // draws straight over the bottom of the text field. `imePadding`
        // shrinks the editor viewport instead, which also gives Compose's
        // cursor bring-into-view something to scroll against. `innerPadding`
        // already carries the navigation-bar inset the IME inset also spans,
        // so consume it first or the two stack into a dead strip above the
        // keyboard.
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = ui.title, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = tryClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { previewing = !previewing }) {
                        Icon(
                            imageVector = if (previewing) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (previewing) "Edit" else "Preview",
                        )
                    }
                    IconButton(
                        onClick = viewModel::save,
                        enabled = !ui.isSaving && !ui.isLoadingBody,
                    ) {
                        if (ui.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(4.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            when {
                ui.isLoadingBody -> {
                    LoadingState()
                }

                previewing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        MarkdownView(markdown = fieldValue.text, imageBaseUrl = imageBaseUrl)
                    }
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MarkdownToolbar(
                            onAction = { action -> updateField(action(fieldValue)) },
                            onInsertImage = { showAttachmentPicker = true },
                        )
                        HorizontalDivider()
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = updateField,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            textStyle = bodyStyle,
                            keyboardOptions = KeyboardOptions.Default,
                            keyboardActions = KeyboardActions.Default,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    onAction: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onInsertImage: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ToolbarButton(Icons.Filled.Title, "Heading") { onAction(MarkdownToolbarActions::heading) }
        ToolbarButton(Icons.Filled.FormatBold, "Bold") { onAction(MarkdownToolbarActions::bold) }
        ToolbarButton(Icons.Filled.FormatItalic, "Italic") { onAction(MarkdownToolbarActions::italic) }
        ToolbarButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list") {
            onAction(MarkdownToolbarActions::bullet)
        }
        ToolbarButton(Icons.Filled.FormatListNumbered, "Numbered list") {
            onAction(MarkdownToolbarActions::numbered)
        }
        ToolbarButton(Icons.Filled.CheckBox, "Checklist") { onAction(MarkdownToolbarActions::checklist) }
        ToolbarButton(Icons.Filled.Link, "Link") { onAction(MarkdownToolbarActions::link) }
        ToolbarButton(Icons.Filled.Image, "Insert image", onClick = onInsertImage)
        ToolbarButton(Icons.Filled.Code, "Inline code") { onAction(MarkdownToolbarActions::inlineCode) }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}
