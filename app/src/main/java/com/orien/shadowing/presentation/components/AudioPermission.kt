package com.orien.shadowing.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Runtime permission helper for microphone access.
 */
@Stable
class AudioPermissionState(
    val hasPermission: Boolean,
    val shouldShowRationale: Boolean,
    val request: () -> Unit
)

@Composable
fun rememberAudioPermission(): AudioPermissionState {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var shouldShowRationale by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        shouldShowRationale = !granted &&
            (activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true)
    }

    return remember(hasPermission, shouldShowRationale) {
        AudioPermissionState(
            hasPermission = hasPermission,
            shouldShowRationale = shouldShowRationale,
            request = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }
}

@Composable
fun PermissionDeniedContent(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    androidx.compose.material3.Card {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Icon(
                Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.Text(
                text = "Microphone permission is required",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Text(
                text = "Recording practice audio requires microphone access. Grant the permission or open system settings.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onRequest) {
                androidx.compose.material3.Text("Request again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(onClick = onOpenSettings) {
                androidx.compose.material3.Text("Open settings")
            }
        }
    }
}
