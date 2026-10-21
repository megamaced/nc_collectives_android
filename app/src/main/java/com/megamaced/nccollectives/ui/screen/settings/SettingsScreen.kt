package com.megamaced.nccollectives.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.auth.AccountSummary
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.StartScreen
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.SyncStatus
import com.megamaced.nccollectives.data.prefs.TextScale
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.attachment.openInBrowser
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.resolve
import com.megamaced.nccollectives.util.syncStatusLines
import com.megamaced.nccollectives.util.uiText
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://github.com/megamaced/nc_collectives_android"
private const val LICENCE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val manualSync by viewModel.manualSync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // Account the user has asked to switch to / remove, held while the
    // confirmation is up. Both are destructive to the local cache, so
    // neither happens on the first tap.
    var pendingSwitch by remember { mutableStateOf<AccountSummary?>(null) }
    var pendingRemoval by remember { mutableStateOf<AccountSummary?>(null) }
    val pendingEdits by viewModel.pendingEditCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val linkScope = rememberCoroutineScope()

    val resources by rememberUpdatedState(LocalResources.current)

    fun openLink(url: String) {
        if (!openInBrowser(context, url.toUri())) {
            linkScope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.settings_no_browser, url)) }
        }
    }

    // Side-effects for terminal manual-update-check states. UpdateAvailable
    // → open the release page in a Custom Tab and reset; UpToDate / Failed
    // → snackbar then reset. Drives off `updateCheck` itself so a fresh
    // tap re-triggers cleanly each time.
    LaunchedEffect(updateCheck) {
        when (val state = updateCheck) {
            is UpdateCheckUiState.UpdateAvailable -> {
                // Issue #62: no browser is an answer to show, not a crash.
                if (!openInBrowser(context, state.htmlUrl.toUri())) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.update_no_browser, state.htmlUrl))
                }
                viewModel.dismissUpdateCheck()
            }

            is UpdateCheckUiState.UpToDate -> {
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.update_check_up_to_date, BuildConfig.VERSION_NAME),
                )
                viewModel.dismissUpdateCheck()
            }

            is UpdateCheckUiState.Failed -> {
                snackbarHostState.showSnackbar(state.message.resolve(resources))
                viewModel.dismissUpdateCheck()
            }

            UpdateCheckUiState.Checking, UpdateCheckUiState.Idle -> {
                Unit
            }
        }
    }

    // "Sync now" reports through the same snackbar. The status line above it
    // updates on its own from DataStore, so success only needs an
    // acknowledgement — the detail is already on screen.
    LaunchedEffect(manualSync) {
        when (val state = manualSync) {
            ManualSyncUiState.Done -> {
                snackbarHostState.showSnackbar(resources.getString(R.string.sync_complete))
                viewModel.dismissManualSync()
            }

            is ManualSyncUiState.Failed -> {
                snackbarHostState.showSnackbar(state.message.resolve(resources))
                viewModel.dismissManualSync()
            }

            ManualSyncUiState.Syncing, ManualSyncUiState.Idle -> {
                Unit
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_accounts))
            if (ui.accounts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_not_signed_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    ui.accounts.forEach { account ->
                        AccountRow(
                            account = account,
                            isActive = account.id == ui.activeAccountId,
                            onSwitch = {
                                viewModel.loadPendingEditCount()
                                pendingSwitch = account
                            },
                            onRemove = {
                                viewModel.loadPendingEditCount()
                                pendingRemoval = account
                            },
                        )
                    }
                }
                if (ui.accounts.size > 1) {
                    Text(
                        text = stringResource(R.string.settings_accounts_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Box(modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.settings_add_account)) }
            }

            SectionBreak()

            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeModeOptions(
                selected = ui.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            Text(
                stringResource(R.string.settings_text_size_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextScaleOptions(
                selected = ui.textScale,
                onSelect = viewModel::setTextScale,
            )

            SectionBreak()

            SectionHeader(stringResource(R.string.settings_startup))
            Text(
                stringResource(R.string.settings_startup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StartupOptions(
                collectives = ui.collectives,
                startScreen = ui.startScreen,
                selectedCollectiveId = ui.defaultCollectiveId,
                onSelectScreen = viewModel::setStartScreen,
                onSelectCollective = viewModel::setDefaultCollective,
            )

            SectionBreak()

            SectionHeader(stringResource(R.string.settings_sync))
            Text(
                stringResource(R.string.settings_sync_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncCadenceOptions(
                selected = ui.syncCadence,
                onSelect = viewModel::setSyncCadence,
            )
            SyncStatusRow(
                status = syncStatus,
                state = manualSync,
                onSyncNow = viewModel::syncNow,
            )

            SectionBreak()

            SectionHeader(stringResource(R.string.settings_editor))
            Text(
                stringResource(R.string.settings_editor_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorPreferenceOptions(
                selected = ui.editorPreference,
                onSelect = viewModel::setEditorPreference,
            )
            Text(
                stringResource(R.string.settings_open_pages_in),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.settings_open_pages_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PageOpenModeOptions(
                selected = ui.pageOpenMode,
                onSelect = viewModel::setPageOpenMode,
            )

            SectionBreak()

            SectionHeader(stringResource(R.string.settings_about))
            Text(
                stringResource(
                    R.string.settings_version,
                    stringResource(R.string.app_name),
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            UpdateCheckRow(
                state = updateCheck,
                onCheck = viewModel::checkForUpdate,
            )
            LinkRow(label = stringResource(R.string.settings_source_code)) { openLink(SOURCE_URL) }
            LinkRow(label = stringResource(R.string.settings_licence)) { openLink(LICENCE_URL) }

            SectionBreak()

            Button(
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Box(modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.settings_sign_out)) }
            }
        }
    }

    pendingSwitch?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(R.string.settings_switch_title, account.loginName)) },
            text = { Text(switchWarning(account, pendingEdits).resolve()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    viewModel.switchToAccount(account.id)
                }) { Text(stringResource(R.string.settings_switch)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pendingRemoval?.let { account ->
        val isActive = account.id == ui.activeAccountId
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.settings_remove_title, account.loginName)) },
            text = { Text(removalWarning(account, isActive, pendingEdits, ui.accounts.size).resolve()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    viewModel.removeAccount(account.id)
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = {
                Text(stringResource(R.string.settings_sign_out_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: AccountSummary,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The active account is not clickable: switching to the account
            // you are already on would wipe its cache and re-download it for
            // nothing.
            .then(
                if (isActive) Modifier else Modifier.clickable(role = Role.Button, onClick = onSwitch),
            ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reserves the slot on every row so the names line up whichever
        // account is active.
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isActive) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_signed_in),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(account.loginName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = account.host,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.RemoveCircleOutline,
                contentDescription = stringResource(R.string.settings_remove_account, account.loginName),
            )
        }
    }
}

/**
 * Copy for the switch confirmation. [pendingEdits] is null while the count
 * is still being read, in which case the sentence about unsynced work is
 * left out rather than guessed at.
 */
internal fun switchWarning(
    account: AccountSummary,
    pendingEdits: Int?,
): UiText = withUnsynced(uiText(R.string.settings_switch_warning, account.loginName), pendingEdits)

internal fun removalWarning(
    account: AccountSummary,
    isActive: Boolean,
    pendingEdits: Int?,
    accountCount: Int,
): UiText {
    val base = when {
        !isActive -> uiText(R.string.settings_remove_other, account.loginName)
        accountCount > 1 -> uiText(R.string.settings_remove_current, account.loginName)
        else -> uiText(R.string.settings_remove_last, account.loginName)
    }
    // Only the active account has a queue: nothing else's data is on the
    // device to have queued writes against.
    return if (isActive) withUnsynced(base, pendingEdits) else base
}

/** [warning], then a sentence about edits that would be lost, when there are any. */
private fun withUnsynced(
    warning: UiText,
    pendingEdits: Int?,
): UiText =
    when {
        pendingEdits == null || pendingEdits == 0 -> warning
        pendingEdits == 1 -> uiText(R.string.settings_two_sentences, warning, uiText(R.string.settings_unsynced_one))
        else -> uiText(R.string.settings_two_sentences, warning, UiText.Plural(R.plurals.settings_unsynced, pendingEdits))
    }

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * The gap between two sections. It is empty, so the column's spacing falls
 * on both sides of it and sections sit twice as far apart as the rows inside
 * them — that and the coloured [SectionHeader] are what mark a new section.
 */
@Composable
private fun SectionBreak() {
    Spacer(Modifier)
}

@Composable
private fun ThemeModeOptions(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column {
        ThemeMode.entries.forEach { mode ->
            RadioRow(
                label = stringResource(themeModeLabel(mode)),
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}

/**
 * Body-text size picker, with a sample rendered at the selected size so
 * the choice can be made without leaving the screen. The sample uses the
 * same `bodyLarge`-times-multiplier arithmetic the page renderer and the
 * native editor use, so what it shows is what a page will look like; the
 * collaborative editor lands close but not pixel-identical — it's
 * Nextcloud Text's stylesheet doing the drawing there.
 */
@Composable
private fun TextScaleOptions(
    selected: TextScale,
    onSelect: (TextScale) -> Unit,
) {
    Column {
        TextScale.entries.forEach { scale ->
            RadioRow(
                label = stringResource(textScaleLabel(scale)),
                selected = selected == scale,
                onClick = { onSelect(scale) },
            )
        }
        val bodyLarge = MaterialTheme.typography.bodyLarge
        Text(
            text = stringResource(R.string.text_size_sample),
            style = bodyLarge.copy(
                fontSize = bodyLarge.fontSize * selected.multiplier,
                lineHeight = bodyLarge.lineHeight * selected.multiplier,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SyncCadenceOptions(
    selected: SyncCadence,
    onSelect: (SyncCadence) -> Unit,
) {
    Column {
        SyncCadence.entries.forEach { cadence ->
            RadioRow(
                label = stringResource(syncCadenceLabel(cadence)),
                selected = selected == cadence,
                onClick = { onSelect(cadence) },
            )
        }
    }
}

@Composable
private fun EditorPreferenceOptions(
    selected: EditorPreference,
    onSelect: (EditorPreference) -> Unit,
) {
    Column {
        EditorPreference.entries.forEach { preference ->
            RadioRow(
                label = stringResource(editorPreferenceLabel(preference)),
                selected = selected == preference,
                onClick = { onSelect(preference) },
            )
        }
    }
}

/**
 * Whether a page opens for reading or straight into the editor: as its
 * collective's page mode says, or always one or the other on this device.
 * A page still opens for reading when it has an unresolved conflict, or its
 * collective can't be edited.
 */
@Composable
internal fun PageOpenModeOptions(
    selected: PageOpenMode,
    onSelect: (PageOpenMode) -> Unit,
) {
    Column {
        PageOpenMode.entries.forEach { mode ->
            RadioRow(
                label = stringResource(pageOpenModeLabel(mode)),
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}

/**
 * Startup-destination picker: Home, the collective list, then one row per
 * cached collective. Radio rows rather than a dropdown to match the rest of
 * this screen; the enclosing Column already scrolls, so a long list is fine.
 */
@Composable
private fun StartupOptions(
    collectives: List<Collective>,
    startScreen: StartScreen,
    selectedCollectiveId: Long?,
    onSelectScreen: (StartScreen) -> Unit,
    onSelectCollective: (Long) -> Unit,
) {
    Column {
        RadioRow(
            label = stringResource(R.string.startup_home),
            selected = selectedCollectiveId == null && startScreen == StartScreen.Home,
            onClick = { onSelectScreen(StartScreen.Home) },
        )
        RadioRow(
            label = stringResource(R.string.startup_collective_list),
            selected = selectedCollectiveId == null && startScreen == StartScreen.CollectiveList,
            onClick = { onSelectScreen(StartScreen.CollectiveList) },
        )
        collectives.forEach { collective ->
            RadioRow(
                label = listOfNotNull(
                    collective.emoji?.takeIf { it.isNotBlank() },
                    collective.name,
                ).joinToString(" "),
                selected = selectedCollectiveId == collective.id,
                onClick = { onSelectCollective(collective.id) },
            )
        }
        if (collectives.isEmpty()) {
            Text(
                stringResource(R.string.startup_no_collectives),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LinkRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * When sync last worked, why it last didn't, and a button to do it now.
 * The two status lines come from a pure formatter so the wording is
 * pinned by tests rather than by reading this screen.
 */
@Composable
private fun SyncStatusRow(
    status: SyncStatus,
    state: ManualSyncUiState,
    onSyncNow: () -> Unit,
) {
    val syncing = state is ManualSyncUiState.Syncing
    // Recomputed whenever the status changes or a sync finishes — enough to
    // keep the line honest without a ticking clock in a settings screen.
    val lines = remember(status, state) { syncStatusLines(status, System.currentTimeMillis()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = lines.summary.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.error?.let { message ->
            Text(
                text = message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !syncing,
                    onClick = onSyncNow,
                    role = Role.Button,
                ).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (syncing) R.string.sync_syncing else R.string.sync_now),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * Tap-to-check row for the manual update affordance. Disabled while a
 * check is in flight and shows a small spinner alongside the label so
 * the user gets immediate feedback that the GitHub round-trip is
 * happening. Terminal outcomes (update available / up-to-date / failed)
 * are surfaced by the caller via the snackbar + browser side-effects.
 */
@Composable
private fun UpdateCheckRow(
    state: UpdateCheckUiState,
    onCheck: () -> Unit,
) {
    val checking = state is UpdateCheckUiState.Checking
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = !checking,
                onClick = onCheck,
                role = Role.Button,
            ).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(if (checking) R.string.update_checking else R.string.update_check),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@StringRes
internal fun themeModeLabel(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
    }

@StringRes
internal fun textScaleLabel(scale: TextScale): Int =
    when (scale) {
        TextScale.Small -> R.string.text_size_small
        TextScale.Default -> R.string.text_size_default
        TextScale.Large -> R.string.text_size_large
        TextScale.Larger -> R.string.text_size_larger
    }

@StringRes
internal fun syncCadenceLabel(cadence: SyncCadence): Int =
    when (cadence) {
        SyncCadence.Off -> R.string.sync_cadence_off
        SyncCadence.Hourly -> R.string.sync_cadence_hourly
        SyncCadence.SixHourly -> R.string.sync_cadence_six_hourly
        SyncCadence.TwiceDaily -> R.string.sync_cadence_twice_daily
        SyncCadence.Daily -> R.string.sync_cadence_daily
    }

@StringRes
internal fun editorPreferenceLabel(preference: EditorPreference): Int =
    when (preference) {
        EditorPreference.PreferPlain -> R.string.editor_prefer_plain
        EditorPreference.PreferCollaborative -> R.string.editor_prefer_collaborative
    }

@StringRes
internal fun pageOpenModeLabel(mode: PageOpenMode): Int =
    when (mode) {
        PageOpenMode.AsCollective -> R.string.open_mode_as_collective
        PageOpenMode.Read -> R.string.open_mode_read
        PageOpenMode.Edit -> R.string.open_mode_edit
    }
