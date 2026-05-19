package com.megamaced.nccollectives.ui.screen.page

import android.webkit.JavascriptInterface
import timber.log.Timber

/**
 * JS-side bridge that the embedded Nextcloud Text editor calls into to
 * report lifecycle events back to the Android host. **The class name
 * matters**: upstream Text checks for `window.DirectEditingMobileInterface`
 * to decide it's running in an embedded WebView rather than a browser.
 *
 * Mirrors the bridge defined in
 * [nextcloud/notes-android `NoteDirectEditFragment.kt`](
 * https://github.com/nextcloud/notes-android/blob/main/app/src/main/java/it/niedermann/owncloud/notes/edit/NoteDirectEditFragment.kt)
 * and
 * [nextcloud/android `EditorWebView.java`](
 * https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/ui/activity/EditorWebView.java)
 * — method names (`loaded`, `close`, `share`) are an upstream contract.
 * If those change in `nextcloud/text`, this stops working silently.
 *
 * Methods are invoked on the WebView's JS thread (not the Android main
 * thread); callbacks here forward to the ViewModel, which is safe to
 * call from any thread (`MutableStateFlow` is thread-safe and
 * `viewModelScope.launch` dispatches appropriately).
 */
internal class DirectEditingMobileInterface(
    private val onLoaded: () -> Unit,
    private val onClose: () -> Unit,
    private val onShare: () -> Unit,
) {
    @JavascriptInterface
    fun loaded() {
        Timber.tag(TAG).d("Editor reported loaded()")
        onLoaded()
    }

    @JavascriptInterface
    fun close() {
        Timber.tag(TAG).d("Editor reported close()")
        onClose()
    }

    @JavascriptInterface
    fun share() {
        Timber.tag(TAG).d("Editor reported share() — not wired to a host action yet")
        onShare()
    }

    companion object {
        /**
         * The exact name the JS bridge is bound under — matches
         * `addJavascriptInterface(…, "DirectEditingMobileInterface")` in
         * Notes-Android and the corresponding `window.…` reference in
         * Text. Don't rename.
         */
        const val NAME = "DirectEditingMobileInterface"

        private const val TAG = "DirectEditingBridge"
    }
}
