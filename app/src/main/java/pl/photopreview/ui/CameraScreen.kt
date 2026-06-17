package pl.photopreview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

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
    var countdown by remember { mutableStateOf<Int?>(null) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var ev by remember { mutableFloatStateOf(0f) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    val zoomLatest by rememberUpdatedState(zoom)

    DisposableEffect(controller) {
        controller.onFrame = { jpeg, rot -> vm.session.submitFrame(jpeg, rot) }

        fun capture() {
            controller.takePhoto { uri ->
                savedMsg = if (uri != null) "Zdjęcie zapisane ✓" else "Błąd zapisu zdjęcia"
                if (uri != null) vm.session.sendPhotoTaken(controller.thumbnailFor(uri) ?: ByteArray(0))
            }
        }

        val shoot: () -> Unit = {
            val timer = vm.config.value.timerSeconds
            if (timer <= 0) {
                capture()
            } else {
                scope.launch {
                    for (s in timer downTo 1) {
                        countdown = s
                        vm.session.sendCountdown(s)
                        delay(1000)
                    }
                    countdown = null
                    vm.session.sendCountdown(0)
                    capture()
                }
            }
        }

        vm.session.onShutter = shoot
        vm.session.onZoom = { z -> zoom = z; controller.setLinearZoom(z) }
        vm.session.onExposure = { e -> ev = e; controller.setExposureFraction(e) }
        vm.session.onTorch = { t -> torch = t; controller.setTorch(t) }
        ShutterKeyBus.onShutter = shoot
        StreamingService.start(context, "Tryb kamery – transmisja podglądu")

        onDispose {
            ShutterKeyBus.onShutter = null
            vm.session.onShutter = null
            vm.session.onZoom = null
            vm.session.onExposure = null
            vm.session.onTorch = null
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
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            val nz = (zoomLatest + (gestureZoom - 1f)).coerceIn(0f, 1f)
                            zoom = nz
                            controller.setLinearZoom(nz)
                        }
                    },
            )
            if (grid) GridOverlay(Modifier.fillMaxSize())
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
            Text(sessionStatusText(status), color = Color.White, style = MaterialTheme.typography.bodySmall)
        }

        countdown?.let {
            Text(
                "$it",
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        savedMsg?.let {
            Surface(color = Color(0xCC000000), modifier = Modifier.align(Alignment.Center)) {
                Text(it, color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            if (hasCamera) {
                ShootingControls(
                    zoom = zoom,
                    onZoom = { zoom = it; controller.setLinearZoom(it) },
                    exposure = ev,
                    onExposure = { ev = it; controller.setExposureFraction(it) },
                    torch = torch,
                    onToggleTorch = { torch = !torch; controller.setTorch(torch) },
                    timerSeconds = config.timerSeconds,
                    onTimer = { vm.setTimer(it) },
                    grid = grid,
                    onToggleGrid = { grid = !grid },
                )
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    val hint = if (status is SessionStatus.Connected) {
                        "Połączono — pilot lub przycisk wyzwala zdjęcie"
                    } else {
                        "Podgląd: " + vm.localAddresses.joinToString().ifEmpty { "—" } + " :${vm.port}"
                    }
                    Text(hint, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
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
                    }) { Text("Hotspot + QR") }
                }
                Button(onClick = {
                    val timer = config.timerSeconds
                    if (timer <= 0) {
                        controller.takePhoto { uri ->
                            savedMsg = if (uri != null) "Zdjęcie zapisane ✓" else "Błąd zapisu zdjęcia"
                            if (uri != null) vm.session.sendPhotoTaken(controller.thumbnailFor(uri) ?: ByteArray(0))
                        }
                    } else {
                        scope.launch {
                            for (s in timer downTo 1) { countdown = s; vm.session.sendCountdown(s); delay(1000) }
                            countdown = null; vm.session.sendCountdown(0)
                            controller.takePhoto { uri ->
                                savedMsg = if (uri != null) "Zdjęcie zapisane ✓" else "Błąd zapisu zdjęcia"
                                if (uri != null) vm.session.sendPhotoTaken(controller.thumbnailFor(uri) ?: ByteArray(0))
                            }
                        }
                    }
                }, modifier = Modifier.height(56.dp)) { Text("MIGAWKA") }
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
