package pl.photopreview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pl.photopreview.SessionStatus
import pl.photopreview.camera.CameraController
import pl.photopreview.input.ShutterKeyBus
import pl.photopreview.net.JoinPayload
import pl.photopreview.service.StreamingService
import pl.photopreview.vm.CameraViewModel
import pl.photopreview.wifi.HotspotInfo

@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: CameraViewModel = viewModel()

    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val hotspotInfo by vm.hotspotInfo.collectAsState()
    val hotspotError by vm.hotspotError.collectAsState()

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) cameraPermLauncher.launch(Manifest.permission.CAMERA) }

    var showQr by remember { mutableStateOf(false) }
    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startHotspot() }

    val controller = remember(lifecycleOwner) { CameraController(context, lifecycleOwner) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        controller.onFrame = { jpeg, rot -> vm.session.submitFrame(jpeg, rot) }
        val shoot: () -> Unit = {
            controller.takePhoto { uri ->
                savedMsg = if (uri != null) "Zdjęcie zapisane ✓" else "Błąd zapisu zdjęcia"
                if (uri != null) {
                    vm.session.sendPhotoTaken(controller.thumbnailFor(uri) ?: ByteArray(0))
                }
            }
        }
        vm.session.onShutter = shoot
        ShutterKeyBus.onShutter = shoot
        StreamingService.start(context, "Tryb kamery – transmisja podglądu")
        onDispose {
            ShutterKeyBus.onShutter = null
            vm.session.onShutter = null
            controller.unbind()
            StreamingService.stop(context)
        }
    }

    LaunchedEffect(config.jpegQuality) { controller.jpegQuality = config.jpegQuality }
    LaunchedEffect(hasCamera, config.analysisHeight) {
        if (hasCamera) runCatching { controller.bind(previewView, config.analysisHeight) }
    }
    LaunchedEffect(savedMsg) { if (savedMsg != null) { delay(1800); savedMsg = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aplikacja potrzebuje dostępu do aparatu.", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Nadaj uprawnienie")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color(0x88000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Wstecz", color = Color.White) }
            Spacer(Modifier.width(8.dp))
            Text(
                sessionStatusText(status),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color(0x88000000)).padding(12.dp),
        ) {
            if (status is SessionStatus.Connected) {
                Text(
                    "Połączono. Naciśnij pilota gimbala (głośność), aby zrobić zdjęcie.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Ta sama sieć Wi-Fi → wpisz w podglądzie IP: " +
                        vm.localAddresses.joinToString().ifEmpty { "—" } + "  (port ${vm.port})",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    } else {
                        Manifest.permission.ACCESS_FINE_LOCATION
                    }
                    if (ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startHotspot()
                    } else {
                        wifiPermLauncher.launch(perm)
                    }
                    showQr = true
                }) { Text("Hotspot + QR (bez routera)") }
            }
        }

        savedMsg?.let {
            Surface(color = Color(0xCC000000), modifier = Modifier.align(Alignment.Center)) {
                Text(it, color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }
    }

    if (showQr) {
        HotspotQrDialog(
            info = hotspotInfo,
            error = hotspotError,
            port = vm.port,
            onDismiss = { showQr = false; vm.stopHotspot() },
        )
    }
}

@Composable
private fun HotspotQrDialog(
    info: HotspotInfo?,
    error: String?,
    port: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
        title = { Text("Połączenie bez routera") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    error != null -> Text(error)
                    info == null -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Uruchamiam hotspot… (wymaga uprawnienia Wi-Fi/lokalizacji)")
                    }
                    else -> {
                        Text("Zeskanuj w trybie Podgląd:")
                        Spacer(Modifier.height(8.dp))
                        QrImage(
                            JoinPayload.build(info.ssid, info.passphrase, port),
                            modifier = Modifier.size(220.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("SSID: ${info.ssid}", style = MaterialTheme.typography.bodySmall)
                        Text("Hasło: ${info.passphrase}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}
