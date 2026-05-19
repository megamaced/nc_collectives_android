package com.megamaced.nccollectives.ui.screen.page

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.BuildConfig
import kotlinx.coroutines.delay

/**
 * Embedded Nextcloud Text editor backed by the Files `directediting` OCS
 * endpoint. POC behind a debug-only entry point on [PageViewScreen]
 * (Batch 28) — Batch 29 will promote it out of debug, gated by a user
 * preference, with the native [PageEditScreen] remaining the offline /
 * older-server fallback.
 *
 * Lifecycle and behaviour mirror what the official Nextcloud Notes
 * Android app ships in `NoteDirectEditFragment` — see [DirectEditingMobileInterface]
 * for the JS-bridge contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageEditWebScreen(
    innerPadding: PaddingValues,
    onClose: () -> Unit,
    viewModel: PageEditWebViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Close-on-success: when the JS bridge has reported close() and the
    // ViewModel has flushed the refresh, pop back to PageView so the
    // observer-driven Flow picks up the autosaved body.
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Closed) onClose()
    }

    // Hard 10-second timeout matching the Notes-Android behaviour
    // (LOAD_TIMEOUT_SECONDS in `NoteDirectEditFragment.kt`). If the
    // editor JS never calls `loaded()` we surface a fallback affordance
    // — Batch 29 will route the user back to the native editor.
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Loaded) {
            delay(EDITOR_TIMEOUT_MS)
            // Re-read after the delay; only escalate if we're still
            // stuck at Loaded (didn't transition to Interactive or
            // Closed in the meantime).
            if (viewModel.uiState.value is PageEditWebUiState.Loaded) {
                val result = snackbarHostState.showSnackbar(
                    message = "Editor is taking a long time to load",
                    actionLabel = "Cancel",
                )
                if (result == SnackbarResult.ActionPerformed) onClose()
            }
        }
    }

    // Surface load failures + retry. Failure is also reachable on a
    // network error in the OCS open call, not just on the WebView load.
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Failed) {
            val msg = (ui as PageEditWebUiState.Failed).message
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.requestSession()
        }
    }

    BackHandler { viewModel.onClose() }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Edit (collaborative)", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize(),
        ) {
            when (val state = ui) {
                is PageEditWebUiState.Loading ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is PageEditWebUiState.Loaded, is PageEditWebUiState.Interactive ->
                    EditorWebView(
                        url = (state as? PageEditWebUiState.Loaded)?.url
                            ?: (state as PageEditWebUiState.Interactive).url,
                        isInteractive = state is PageEditWebUiState.Interactive,
                        onLoaded = viewModel::onEditorReady,
                        onCloseFromJs = viewModel::onClose,
                    )
                is PageEditWebUiState.Failed ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    }
                PageEditWebUiState.Closed -> Unit
            }
        }
    }
}

// `JavascriptInterface` suppressed because lint can't follow the bridge's
// concrete type through Compose's generated wrapper — the methods *are*
// `@JavascriptInterface`-annotated, as `DirectEditingMobileInterface.kt`
// itself enforces. `SetJavaScriptEnabled` suppressed because the embedded
// editor is JS-driven by design.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun EditorWebView(
    url: String,
    isInteractive: Boolean,
    onLoaded: () -> Unit,
    onCloseFromJs: () -> Unit,
) {
    // Concrete type spelled out so the Android Lint
    // [JavascriptInterface] check can see through `remember {}` and
    // verify the @JavascriptInterface annotations on the bridge.
    val bridge: DirectEditingMobileInterface =
        remember(onLoaded, onCloseFromJs) {
            DirectEditingMobileInterface(
                onLoaded = onLoaded,
                onClose = onCloseFromJs,
                // share() isn't wired to a host action yet — log only.
                // A future "Share" overflow menu would consume it.
                onShare = {},
            )
        }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isInteractive) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // Nextcloud server inspects the UA to decide
                        // whether to serve a mobile-friendly variant.
                        // Match the shape Notes-Android uses so we
                        // get the same response.
                        userAgentString = "Mozilla/5.0 (Android) NCCollectives/${BuildConfig.VERSION_NAME} (Mobile)"
                        // Notes-Android explicitly disables file:// to
                        // narrow the WebView's attack surface; we
                        // mirror that posture.
                        @Suppress("DEPRECATION")
                        allowFileAccess = false
                        // Wide-viewport on so the responsive editor
                        // CSS picks the right breakpoint.
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    addJavascriptInterface(bridge, DirectEditingMobileInterface.NAME)
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
        )
    }
}

/**
 * 10-second timeout before we offer the user a way out (Batch 28 just
 * shows "Cancel"; Batch 29 will replace this with a "Switch to plain
 * editor" action). Same value as Notes-Android's
 * `LOAD_TIMEOUT_SECONDS` constant.
 */
private const val EDITOR_TIMEOUT_MS = 10_000L
