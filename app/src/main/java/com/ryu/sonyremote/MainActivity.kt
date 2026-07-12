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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ContentResolver
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryu.sonyremote.ui.CameraRemoteApp
import com.ryu.sonyremote.ui.CameraViewModel
import com.ryu.sonyremote.ui.theme.SonyRemoteTheme

class MainActivity : ComponentActivity() {
    private var incomingLutUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingLutUri = intent.sharedLutUri()
        enableEdgeToEdge()
        setContent {
            SonyRemoteTheme {
                RemoteRoot(
                    incomingLutUri = incomingLutUri,
                    onIncomingLutConsumed = { incomingLutUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLutUri = intent.sharedLutUri()
    }
}

@Composable
private fun RemoteRoot(
    incomingLutUri: Uri?,
    onIncomingLutConsumed: () -> Unit,
    cameraViewModel: CameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val nearbyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraViewModel.onNearbyPermissionResult(granted)
    }

    LaunchedEffect(incomingLutUri) {
        val uri = incomingLutUri ?: return@LaunchedEffect
        try {
            val name = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            context.contentResolver.readSharedLuts(uri, name).forEach { (lutName, text) ->
                cameraViewModel.importCubeLut(lutName, text)
            }
        } catch (error: Throwable) {
            cameraViewModel.reportLutImportFailure(error)
        } finally {
            onIncomingLutConsumed()
        }
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

private fun Intent.sharedLutUri(): Uri? = when (action) {
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        ?: clipData?.getItemAt(0)?.uri
        ?: data
    Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            ?.firstOrNull()
            ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    Intent.ACTION_VIEW -> data
    else -> null
}

private fun ContentResolver.readSharedLuts(uri: Uri, displayName: String): List<Pair<String, String>> {
    val isZip = displayName.endsWith(".zip", ignoreCase = true) ||
        getType(uri) in ZIP_MIME_TYPES
    if (!isZip) {
        val text = openInputStream(uri)?.use { it.readLimitedUtf8() }
            ?: error("Could not open shared LUT")
        return listOf(displayName to text)
    }

    openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            val luts = mutableListOf<Pair<String, String>>()
            repeat(MAX_ZIP_ENTRIES) {
                val entry = zip.nextEntry ?: return luts.ifEmpty {
                    error("The shared ZIP does not contain a .cube LUT")
                }
                if (!entry.isDirectory && entry.name.endsWith(".cube", ignoreCase = true)) {
                    val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    luts += safeName to zip.readLimitedUtf8()
                }
                zip.closeEntry()
            }
        }
    } ?: error("Could not open shared LUT archive")
    error("The shared ZIP contains too many files")
}

private fun InputStream.readLimitedUtf8(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_LUT_BYTES) { "The LUT is larger than 8 MB" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
private const val MAX_ZIP_ENTRIES = 100
private const val MAX_LUT_BYTES = 8 * 1024 * 1024
