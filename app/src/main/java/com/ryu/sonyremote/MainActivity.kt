package com.ryu.sonyremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryu.sonyremote.ui.CameraRemoteApp
import com.ryu.sonyremote.ui.CameraViewModel
import com.ryu.sonyremote.ui.theme.SonyRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonyRemoteTheme {
                RemoteRoot()
            }
        }
    }
}

@Composable
private fun RemoteRoot(cameraViewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val nearbyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraViewModel.onNearbyPermissionResult(granted)
    }

    CameraRemoteApp(
        viewModel = cameraViewModel,
        onOpenWifi = {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            runCatching { wifiPanel.launch(panelIntent) }
                .onFailure { wifiPanel.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        },
        onFindCamera = {
            val needsAndroid16Permission =
                Build.VERSION.SDK_INT >= 36 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsAndroid16Permission) {
                nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                cameraViewModel.onNearbyPermissionResult(true)
            }
        },
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                ),
            )
        },
    )
}
