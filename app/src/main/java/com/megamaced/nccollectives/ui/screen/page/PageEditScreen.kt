package com.megamaced.nccollectives.ui.screen.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageEditScreen(
    innerPadding: PaddingValues,
    onClose: () -> Unit,
    viewModel: PageEditViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var previewing by remember { mutableStateOf(false) }
    var showDiscardPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(ui.initialBody) {
        if (ui.initialBody != null && fieldValue.text.isEmpty()) {
            fieldValue = TextFieldValue(ui.initialBody.orEmpty())
        }
    }

    LaunchedEffect(ui.saveSucceeded) {
        if (ui.saveSucceeded) onClose()
    }

    LaunchedEffect(ui.saveError) {
        ui.saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val hasUnsavedChanges = ui.initialBody != null && fieldValue.text != ui.initialBody
    val tryClose: () -> Unit = {
        if (hasUnsavedChanges) showDiscardPrompt = true else onClose()
    }

    BackHandler(enabled = true, onBack = tryClose)

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
        modifier = Modifier.padding(innerPadding),
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
                        onClick = { viewModel.save(fieldValue.text) },
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
                ui.isLoadingBody -> LoadingState()
                previewing ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        MarkdownView(markdown = fieldValue.text)
                    }
                else ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        MarkdownToolbar(
                            onAction = { fieldValue = it(fieldValue) },
                        )
                        HorizontalDivider()
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { fieldValue = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = KeyboardOptions.Default,
                            keyboardActions = KeyboardActions.Default,
                        )
                    }
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(onAction: ((TextFieldValue) -> TextFieldValue) -> Unit) {
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
