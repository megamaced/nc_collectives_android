package com.megamaced.nccollectives.ui.screen.login

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect
import timber.log.Timber

/**
 * Sign in to a Nextcloud server.
 *
 * Serves both the cold sign-in (mounted by the scaffold when there is no
 * session) and "add another account" from Settings (issue #14) — hence
 * [onCancel], which is null in the first case because there is nowhere to
 * go back to. The screen itself is otherwise identical between the two:
 * `AccountSwitcher.signInTo` works out from the credential store which of
 * them is happening.
 */
@Composable
fun LoginScreen(
    innerPadding: PaddingValues = PaddingValues(),
    onCancel: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.loginUrl) {
        uiState.loginUrl?.let { url -> launchCustomTab(context, url) }
    }

    SnackbarStatusEffect(uiState.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        // Zero when the scaffold mounts this directly as the signed-out
        // screen; the authenticated host's padding when it is the "add
        // account" destination, same as every other screen in the nav host.
        modifier = Modifier.padding(innerPadding),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "NC Collectives",
                style = MaterialTheme.typography.headlineLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect to your Nextcloud server",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = uiState.hostInput,
                onValueChange = viewModel::onHostChanged,
                label = { Text("Server URL") },
                placeholder = { Text("cloud.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.startLogin() }),
                enabled = !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = viewModel::startLogin,
                enabled = !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Log in")
                }
            }

            if (uiState.isPolling) {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for authorisation…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                // Disabled mid-flow: the Custom Tab is already open and the
                // poll is running, and backing out from under it would leave
                // an app password issued server-side that this device never
                // stored.
                TextButton(
                    onClick = onCancel,
                    enabled = !uiState.isLoading && !uiState.isPolling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

private fun launchCustomTab(
    context: Context,
    url: String,
) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    // S-26: `launchUrl` resolves whatever scheme it is handed through the
    // system, so a server-supplied `intent:` / custom-scheme URL would
    // start another app rather than a browser tab. `LoginViewModel` already
    // refuses a login URL that isn't https on the host the user typed; this
    // gate is the scheme half, held locally where the launch happens.
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
        Timber.w("Refusing to open a login URL with scheme=%s", uri?.scheme)
        return
    }
    val intent = CustomTabsIntent.Builder().build()
    intent.launchUrl(context, uri)
}
