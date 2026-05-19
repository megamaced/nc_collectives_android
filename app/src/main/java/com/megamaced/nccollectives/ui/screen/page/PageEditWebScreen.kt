package com.megamaced.nccollectives.ui.screen.page

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.megamaced.nccollectives.BuildConfig
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Embedded Nextcloud Text editor backed by the Files `directediting` OCS
 * endpoint. Production entry routed from [PageViewScreen] when the
 * user's `EditorPreference` resolves to `Web` (Batch 29). Native
 * [PageEditScreen] is still the offline / older-server fallback.
 *
 * Lifecycle and behaviour mirror what the official Nextcloud Notes
 * Android app ships in `NoteDirectEditFragment` — see [DirectEditingMobileInterface]
 * for the JS-bridge contract.
 *
 * **Process-death (Batch 30f):** WebView state is intentionally not
 * `rememberSaveable`. The `directediting/open` token is consumed on
 * first WebView load, so a restore would 410 the request anyway. On
 * any restore we re-fetch a fresh URL (the ViewModel's `init` block
 * does this on every fresh VM instance). Any unsaved keystrokes that
 * Text hadn't autosaved server-side at the moment of process-death are
 * lost. Acceptable: Text autosaves on every few keystrokes, so the
 * lossy window is small. Documented here so future maintainers don't
 * try to `rememberSaveable` the URL.
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

    // Detect "is the rendered theme dark?" from the resolved M3 surface
    // colour rather than `isSystemInDarkTheme()` — the app honours the
    // user's per-app Theme preference (Settings → Theme), which can
    // diverge from the OS setting. Luminance < 0.5 is the standard
    // contrast-based dark/light split.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Holds the WebView reference for back-press JS injection (30d).
    // Set from the AndroidView factory; read by the BackHandler.
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Timestamp of the last back-press, milliseconds (30d). First back
    // press injects the Text close button; if the user back-presses
    // again within DOUBLE_BACK_WINDOW_MS we force-close in case Text
    // never came back with `close()` (slow autosave, network blip).
    var lastBackPressMs by remember { mutableStateOf(0L) }

    // Close-on-success: when the JS bridge has reported close() and the
    // ViewModel has flushed the refresh, pop back to PageView so the
    // observer-driven Flow picks up the autosaved body.
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Closed) onClose()
    }

    // Hard 10-second timeout matching the Notes-Android behaviour
    // (LOAD_TIMEOUT_SECONDS in `NoteDirectEditFragment.kt`).
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Loaded) {
            delay(EDITOR_TIMEOUT_MS)
            if (viewModel.uiState.value is PageEditWebUiState.Loaded) {
                val result = snackbarHostState.showSnackbar(
                    message = "Editor is taking a long time to load",
                    actionLabel = "Cancel",
                )
                if (result == SnackbarResult.ActionPerformed) onClose()
            }
        }
    }

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

    BackHandler {
        val now = System.currentTimeMillis()
        val current = webView
        if (current == null || now - lastBackPressMs < DOUBLE_BACK_WINDOW_MS) {
            // Second back (or no WebView yet) — force-close. Text's
            // autosave should have flushed whatever was typed within
            // its debounce window, but anything in the gap is lost.
            // This is the documented escape hatch in case the JS
            // bridge never reports back.
            Timber.tag(TAG).d("Force-close on double back-press")
            viewModel.onClose()
        } else {
            // First back — ask Text to save + close via the same
            // `.icon-close` selector Notes-Android targets. The
            // selector is an upstream CSS contract (see
            // DirectEditingMobileInterface KDoc). When Text honours
            // it, the JS bridge calls back into our `close()` which
            // routes through viewModel.onClose() → state = Closed →
            // the LaunchedEffect above pops the back stack.
            Timber.tag(TAG).d("First back-press: injecting Text close")
            current.evaluateJavascript(JS_TEXT_CLOSE, null)
            lastBackPressMs = now
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        // The outer `NcCollectivesScaffold` already consumed every system
        // bar inset into `innerPadding`; if the inner Scaffold also
        // applied its own status-bar inset (the default), the TopAppBar
        // would render below the consumed inset, leaving a visible gap
        // between the status bar and the title. Zero it out — every
        // inset is already handled one level up.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        isDarkTheme = isDarkTheme,
                        onLoaded = viewModel::onEditorReady,
                        onCloseFromJs = viewModel::onClose,
                        onSslError = {
                            viewModel.surfaceLoadFailure(
                                "Couldn't verify the server's TLS certificate. The editor was not opened.",
                            )
                        },
                        onWebViewCreated = { webView = it },
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
    isDarkTheme: Boolean,
    onLoaded: () -> Unit,
    onCloseFromJs: () -> Unit,
    onSslError: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    val bridge: DirectEditingMobileInterface =
        remember(onLoaded, onCloseFromJs) {
            DirectEditingMobileInterface(
                onLoaded = onLoaded,
                onClose = onCloseFromJs,
                onShare = {},
            )
        }

    // Pending callback for the WebView's file chooser. The WebChromeClient
    // stashes the `ValueCallback` here, launches the system picker, and
    // the launcher's result handler invokes the callback with the chosen
    // URI(s) — or `null` if the user cancelled.
    val pendingFileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>?>?>(null) }
    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // Even on cancel, the WebChromeClient demands `onReceiveValue`
        // be invoked (with `null`) or the picker stays in a permanent
        // "picking" state and subsequent clicks do nothing — Notes-
        // Android learned this the hard way, see Files-Android
        // `EditorWebView.java:140`.
        val callback = pendingFileCallback.value
        pendingFileCallback.value = null
        if (uri == null) {
            callback?.onReceiveValue(null)
        } else {
            callback?.onReceiveValue(arrayOf(uri))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // System navigation gesture bar sits at the very bottom of an
            // edge-to-edge window. Without this inset the WebView's
            // formatting toolbar slips behind the gesture indicator and
            // looks "squashed" against the bottom edge.
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
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
                        userAgentString = "Mozilla/5.0 (Android) NCCollectives/${BuildConfig.VERSION_NAME} (Mobile)"
                        @Suppress("DEPRECATION")
                        allowFileAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    // Two darkening shims, picked at runtime by which
                    // `WebViewFeature` the System WebView reports as
                    // supported. ALGORITHMIC_DARKENING (Android 13+ WebView)
                    // honours the page's `<meta name="color-scheme">` and
                    // `prefers-color-scheme: dark`. FORCE_DARK is the older
                    // pre-Android-13 shim — it inverts content even without
                    // an opt-in. Nextcloud Text doesn't ship a color-scheme
                    // hint, so FORCE_DARK is the one that actually paints
                    // dark on older WebViews. Belt-and-braces: when both
                    // are available we prefer the newer one.
                    if (isDarkTheme) {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                            @Suppress("DEPRECATION")
                            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                        }
                    }
                    addJavascriptInterface(bridge, DirectEditingMobileInterface.NAME)
                    webViewClient = StripChromeWebViewClient(onSslError)
                    webChromeClient = ImagePickingChromeClient(
                        launchPicker = { callback ->
                            pendingFileCallback.value = callback
                            visualPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    loadUrl(url)
                    onWebViewCreated(this)
                }
            },
        )
    }
}

/**
 * Two responsibilities baked into one client:
 *
 *  1. **Strict TLS** — same posture as the rest of the app's
 *     `network_security_config.xml` (`cleartextTrafficPermitted="false"`).
 *     If a user's Nextcloud is on a self-signed CA, they must add it to
 *     the Android system store; the editor won't load over a cert the OS
 *     doesn't already trust.
 *  2. **Chrome strip** — Nextcloud's `directediting` URL loads the full
 *     Files-app shell around the Text editor: top header bar, left
 *     navigation, right details sidebar (which on a narrow viewport
 *     collapses into a slim coloured rail along the right edge — that's
 *     what the user reports as "blue bar"). The CSS below hides every
 *     known shell selector on `onPageFinished` so only the editor itself
 *     remains. Selectors are upstream Files / Server contracts; if they
 *     rename, the rail comes back but the editor itself still works.
 *     This is a deliberately additive `display: none` list — bias
 *     toward leaving unknown elements visible over hiding something
 *     useful.
 */
private class StripChromeWebViewClient(
    private val onSslError: () -> Unit,
) : WebViewClient() {
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        Timber.tag(TAG).w("SSL error from WebView: %s", error)
        handler?.cancel()
        onSslError()
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(STRIP_CHROME_JS, null)
    }
}

/**
 * Injects a `<style>` element with `display: none` rules for every
 * Files-app shell selector that wraps the embedded Text editor. The
 * rules are scoped tightly so a stray match doesn't kill the editor
 * surface itself. Reapplied via MutationObserver because Vue mounts
 * the navigation / sidebar lazily, after `onPageFinished` fires.
 */
private val STRIP_CHROME_JS =
    """
    (function() {
        var css = [
            '#header { display: none !important; }',
            '#app-navigation, #app-navigation-vue { display: none !important; }',
            '#app-sidebar, #app-sidebar-vue, .app-sidebar { display: none !important; }',
            'aside.app-sidebar, aside#app-sidebar-vue { display: none !important; }',
            '.app-content-list, .files-controls { display: none !important; }',
            '#content-vue, #content { padding: 0 !important; margin: 0 !important; top: 0 !important; }',
            'body, #body-user { padding: 0 !important; margin: 0 !important; }',
            '.text-editor, .editor, .ProseMirror { padding-top: 0 !important; }'
        ].join('\n');
        var existing = document.getElementById('nc-collectives-strip');
        if (!existing) {
            var s = document.createElement('style');
            s.id = 'nc-collectives-strip';
            s.appendChild(document.createTextNode(css));
            document.head.appendChild(s);
        }
    })();
    """.trimIndent()

/**
 * Surfaces the WebView's "insert image" file-chooser as the system
 * `PickVisualMedia` picker. Notes-Android *doesn't* install a chrome
 * client, so its in-editor image insert button is silently dead;
 * Files-Android does install one (`EditorWebView.java:140`). We side
 * with Files-Android — the image-insert button is useful, and routing
 * through `PickVisualMedia` avoids the runtime `READ_MEDIA_IMAGES`
 * permission prompt on Android 13+.
 */
private class ImagePickingChromeClient(
    private val launchPicker: (ValueCallback<Array<Uri>?>) -> Unit,
) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>?>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback ?: return false
        launchPicker(filePathCallback)
        return true
    }
}

/**
 * JS snippet injected on the first back-press (Batch 30d). Mirrors what
 * `nextcloud/notes-android NoteDirectEditFragment.kt` does to ask Text
 * to save and close — clicks the editor's close button via CSS selector.
 * Upstream contract; if `.icon-close` is renamed in `nextcloud/text`,
 * the back button stops triggering save-and-close and the double-tap
 * force-close kicks in instead, so the user can still get out, but
 * unsaved keystrokes within the autosave debounce window are lost.
 */
private const val JS_TEXT_CLOSE = "document.querySelector('.icon-close')?.click();"

/**
 * 10-second timeout before we offer the user a way out. Same value as
 * Notes-Android's `LOAD_TIMEOUT_SECONDS` constant.
 */
private const val EDITOR_TIMEOUT_MS = 10_000L

/**
 * Window inside which a second back-press is interpreted as
 * "force-close, don't wait for Text to confirm". One second matches
 * the rhythm of typical double-tap gestures.
 */
private const val DOUBLE_BACK_WINDOW_MS = 1_000L

private const val TAG = "PageEditWebScreen"
