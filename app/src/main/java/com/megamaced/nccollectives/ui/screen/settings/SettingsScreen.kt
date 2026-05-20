package com.megamaced.nccollectives.ui.screen.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.ThemeMode

private const val SOURCE_URL = "https://github.com/megamaced/nc_collectives_android"
private const val LICENCE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val updateCheck by viewModel.updateCheck.collectAsState()
    val context = LocalContext.current
    var showSignOutConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Side-effects for terminal manual-update-check states. UpdateAvailable
    // → open the release page in a Custom Tab and reset; UpToDate / Failed
    // → snackbar then reset. Drives off `updateCheck` itself so a fresh
    // tap re-triggers cleanly each time.
    LaunchedEffect(updateCheck) {
        when (val state = updateCheck) {
            is UpdateCheckUiState.UpdateAvailable -> {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(state.htmlUrl))
                viewModel.dismissUpdateCheck()
            }
            is UpdateCheckUiState.UpToDate -> {
                snackbarHostState.showSnackbar(
                    "You're on the latest version (${BuildConfig.VERSION_NAME}).",
                )
                viewModel.dismissUpdateCheck()
            }
            is UpdateCheckUiState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.dismissUpdateCheck()
            }
            UpdateCheckUiState.Checking, UpdateCheckUiState.Idle -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionHeader("Account")
            val account = ui.account
            if (account != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(account.loginName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        account.host,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Not signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            SectionHeader("Appearance")
            ThemeModeOptions(
                selected = ui.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            HorizontalDivider()

            SectionHeader("Sync")
            Text(
                "How often the app refreshes pages and metadata in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncCadenceOptions(
                selected = ui.syncCadence,
                onSelect = viewModel::setSyncCadence,
            )

            HorizontalDivider()

            SectionHeader("Editor")
            Text(
                "Plain markdown works offline and is the reliable default. " +
                    "The collaborative editor (Nextcloud Text) is in beta and " +
                    "only available when online with a supported server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorPreferenceOptions(
                selected = ui.editorPreference,
                onSelect = viewModel::setEditorPreference,
            )

            HorizontalDivider()

            SectionHeader("About")
            Text(
                "NC Collectives ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            UpdateCheckRow(
                state = updateCheck,
                onCheck = viewModel::checkForUpdate,
            )
            LinkRow(label = "Source code") {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(SOURCE_URL))
            }
            LinkRow(label = "Licence (AGPL-3.0)") {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(LICENCE_URL))
            }

            HorizontalDivider()

            Button(
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Box(modifier = Modifier.padding(start = 8.dp)) { Text("Sign out") }
            }
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Cached pages, attachments, and queued edits will be removed from this device. " +
                        "Anything not yet synced will be lost.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
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

@Composable
private fun ThemeModeOptions(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column {
        ThemeMode.entries.forEach { mode ->
            RadioRow(
                label = when (mode) {
                    ThemeMode.System -> "Follow system"
                    ThemeMode.Light -> "Light"
                    ThemeMode.Dark -> "Dark"
                },
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
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
                label = when (cadence) {
                    SyncCadence.Off -> "Off — manual refresh only"
                    SyncCadence.Hourly -> "Every hour"
                    SyncCadence.SixHourly -> "Every 6 hours (default)"
                    SyncCadence.TwiceDaily -> "Every 12 hours"
                    SyncCadence.Daily -> "Once a day"
                },
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
                label = when (preference) {
                    EditorPreference.PreferPlain -> "Prefer plain markdown (default)"
                    EditorPreference.PreferCollaborative -> "Prefer collaborative (beta, when online)"
                },
                selected = selected == preference,
                onClick = { onSelect(preference) },
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
            text = if (checking) "Checking for updates…" else "Check for updates",
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
