package com.megamaced.nccollectives.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.megamaced.nccollectives.R

@Composable
internal fun LocalNetworkAccess(enabled: Boolean = true) {
    if (Build.VERSION.SDK_INT < 37) return

    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    var openSettings by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        openSettings = !allowed && activity?.shouldShowRequestPermissionRationale(permission) == false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermission()
        if (granted) openSettings = false
    }

    if (granted) return

    // A public hostname may resolve to a LAN address on the user's Wi-Fi.
    // Let the user opt in independently of how the server address is written.
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (openSettings) R.string.local_network_settings_note else R.string.local_network_access_note,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                if (openSettings) {
                    val uri = "package:${context.packageName}".toUri()
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                } else {
                    launcher.launch(permission)
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (openSettings) R.string.local_network_open_settings else R.string.local_network_allow))
        }
    }
}
