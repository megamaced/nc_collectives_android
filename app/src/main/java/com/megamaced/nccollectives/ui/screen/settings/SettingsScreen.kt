package com.megamaced.nccollectives.ui.screen.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.ThemeMode

private const val SOURCE_URL = "https://github.com/megamaced/nc_collectives_android"
private const val LICENCE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

@Composable
internal fun SettingsScreen(
    innerPadding: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
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

        SectionHeader("About")
        Text(
            "NC Collectives ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
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
            .clickable(onClick = onClick)
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
