package com.megamaced.nccollectives.ui.screen.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.canHoldChildren

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareCaptureScreen(
    innerPadding: PaddingValues,
    onDismiss: () -> Unit,
    viewModel: ShareCaptureViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val collectives by viewModel.collectives.collectAsState()
    val pages by viewModel.pagesForCollective.collectAsState()

    // Auto-pick the only collective if there's just one (the common case).
    LaunchedEffect(collectives) {
        if (ui.selectedCollectiveId == null && collectives.size == 1) {
            viewModel.selectCollective(collectives.first().id)
        }
    }

    LaunchedEffect(ui.finished) {
        if (ui.finished) onDismiss()
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Share to collective", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val payload = ui.payload
            if (payload == null) {
                Text(
                    text = "Nothing to share — return to the previous app and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            SharedContentPreview(payload, modifier = Modifier.fillMaxWidth())

            CollectivePicker(
                collectives = collectives,
                selectedId = ui.selectedCollectiveId,
                onSelect = viewModel::selectCollective,
            )

            ModeToggle(mode = ui.mode, onChange = viewModel::setMode)

            when (ui.mode) {
                ShareMode.NEW_PAGE -> NewPageSection(
                    title = ui.title,
                    onTitleChange = viewModel::setTitle,
                    pages = pages,
                    selectedParentId = ui.selectedParentPageId,
                    onParentChange = viewModel::selectParent,
                )
                ShareMode.APPEND -> AppendSection(
                    pages = pages,
                    selectedPageId = ui.selectedAppendPageId,
                    onSelect = viewModel::selectAppendTarget,
                )
            }

            ui.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.finishedMessage?.takeIf { ui.finished }?.let { Text(it) }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::submit,
                enabled = !ui.isSaving &&
                    ui.selectedCollectiveId != null &&
                    (ui.mode == ShareMode.NEW_PAGE || ui.selectedAppendPageId != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.isSaving) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(if (ui.mode == ShareMode.NEW_PAGE) "Create page" else "Append")
                }
            }
        }
    }
}

@Composable
private fun SharedContentPreview(
    payload: com.megamaced.nccollectives.share.SharePayload,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("From the share sheet", style = MaterialTheme.typography.labelMedium)
        payload.text?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(500), style = MaterialTheme.typography.bodyMedium)
        }
        if (payload.images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(payload.images, key = { it.toString() }) { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(80.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectivePicker(
    collectives: List<Collective>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = collectives.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Collective") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            collectives.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = {
                        onSelect(c.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(
    mode: ShareMode,
    onChange: (ShareMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ShareMode.NEW_PAGE,
            onClick = { onChange(ShareMode.NEW_PAGE) },
            label = { Text("New page") },
        )
        FilterChip(
            selected = mode == ShareMode.APPEND,
            onClick = { onChange(ShareMode.APPEND) },
            label = { Text("Append to existing") },
        )
    }
}

@Composable
private fun NewPageSection(
    title: String,
    onTitleChange: (String) -> Unit,
    pages: List<Page>,
    selectedParentId: Long?,
    onParentChange: (Long) -> Unit,
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text("Page title") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text("Parent page", style = MaterialTheme.typography.labelMedium)
    HorizontalDivider()
    val folderTargets = pages.filter { it.canHoldChildren() }
    if (folderTargets.isEmpty()) {
        Text(
            text = "No folder pages in this collective. Promote a leaf to a folder in the web UI first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        folderTargets.forEach { page ->
            PageSelectableRow(
                page = page,
                selected = page.id == selectedParentId,
                onClick = { onParentChange(page.id) },
            )
        }
    }
}

@Composable
private fun AppendSection(
    pages: List<Page>,
    selectedPageId: Long?,
    onSelect: (Long) -> Unit,
) {
    Text("Append to", style = MaterialTheme.typography.labelMedium)
    HorizontalDivider()
    if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        pages.forEach { page ->
            PageSelectableRow(
                page = page,
                selected = page.id == selectedPageId,
                onClick = { onSelect(page.id) },
            )
        }
    }
}

@Composable
private fun PageSelectableRow(
    page: Page,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = listOfNotNull(
                    page.emoji?.takeIf { it.isNotBlank() },
                    page.title,
                ).joinToString(" "),
            )
        },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
