package com.megamaced.nccollectives.ui.attachment

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps `ActivityResultContracts.TakePicture` so the AttachmentsScreen can
 * fire the system camera with a single `launch()` call. The capture lands
 * in the app's internal cache under `attachments/` and is exposed back to
 * the rest of the app via [androidx.core.content.FileProvider] using the
 * `${applicationId}.fileprovider` authority declared in the manifest.
 *
 * We deliberately use the system camera (not CameraX) — a wiki/notebook
 * app gains nothing from an in-app preview surface, and routing through
 * the system app avoids dragging in an in-app preview UI plus the
 * additional camera permission.
 */
@Composable
fun rememberCameraCapture(onCaptured: (Uri, String) -> Unit): CameraCapture {
    val context = LocalContext.current
    val pendingUri = remember { mutableStateOf<Uri?>(null) }
    val pendingName = remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingUri.value
        val name = pendingName.value
        pendingUri.value = null
        pendingName.value = null
        if (success && uri != null && name != null) {
            onCaptured(uri, name)
        }
    }

    return remember(context) {
        CameraCapture {
            val (uri, fileName) = newCaptureFile(context)
            pendingUri.value = uri
            pendingName.value = fileName
            launcher.launch(uri)
        }
    }
}

class CameraCapture internal constructor(
    private val launchAction: () -> Unit,
) {
    fun launch() = launchAction()
}

private fun newCaptureFile(context: Context): Pair<Uri, String> {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "capture-$timestamp.jpg"
    val file = File(dir, name)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    return uri to name
}
