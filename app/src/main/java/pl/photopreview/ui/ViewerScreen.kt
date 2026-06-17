package pl.photopreview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import pl.photopreview.SessionStatus
import pl.photopreview.StreamConfig
import pl.photopreview.vm.ViewerViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    val vm: ViewerViewModel = viewModel()
    val status by vm.status.collectAsState()
    val frame by vm.frame.collectAsState()
    val countdown by vm.countdown.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val config by vm.config.collectAsState()
    val photoThumb by vm.photoThumb.collectAsState()
    val photoSaved by vm.photoSaved.collectAsState()
    val saveMsg by vm.saveMsg.collectAsState()
    val qrError by vm.qrError.collectAsState()
    val zoom by vm.zoom.collectAsState()
    val exposure by vm.exposure.collectAsState()
    val torch by vm.torch.collectAsState()
    val grid by vm.grid.collectAsState()
    val zoomLatest by rememberUpdatedState(zoom)

    val context = LocalContext.current
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            writeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    var manualIp by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.connectViaQr(it) }
    }

    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var count = 0
        var last = System.currentTimeMillis()
        vm.frame.collect {
            count++
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                fps = count; count = 0; last = now
            }
        }
    }

    val df = frame
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (df != null) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        vm.setZoom((zoomLatest + (gestureZoom - 1f)).coerceIn(0f, 1f))
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val bmp = df.bitmap
                    val rot = ((df.rotationDegrees % 360) + 360) % 360
                    val rotated = rot == 90 || rot == 270
                    val contentW = if (rotated) bmp.height else bmp.width
                    val contentH = if (rotated) bmp.width else bmp.height
                    if (contentW > 0 && contentH > 0) {
                        val scale = minOf(size.width / contentW, size.height / contentH)
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        withTransform({ rotate(rot.toFloat(), pivot = center) }) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(
                                    (center.x - drawW / 2f).roundToInt(),
                                    (center.y - drawH / 2f).roundToInt(),
                                ),
                                dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                            )
                        }
                    }
                }
                if (grid) GridOverlay(Modifier.fillMaxSize())
            }
        } else {
            ConnectPanel(
                status = status,
                manualIp = manualIp,
                onManualIpChange = { manualIp = it },
                onConnectManual = { vm.connectManual(manualIp) },
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setOrientationLocked(false)
                            .setBeepEnabled(false)
                            .setPrompt("Zeskanuj kod QR z telefonu-kamery"),
                    )
                },
                onDiscover = { vm.startDiscovery() },
                discovered = discovered,
                onConnectDiscovered = { vm.connectDiscovered() },
                qrError = qrError,
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color(0x88000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Wstecz", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                sessionStatusText(status) + if (df != null) "  •  $fps fps" else "",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            if (df != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showSettings = true }) { Text("Ustawienia", color = Color.White) }
            }
        }

        countdown?.let {
            Text(
                "$it",
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (df != null) {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ExtendedFloatingActionButton(onClick = { vm.sendShutter() }) { Text("MIGAWKA") }
                }
                ShootingControls(
                    zoom = zoom,
                    onZoom = { vm.setZoom(it) },
                    exposure = exposure,
                    onExposure = { vm.setExposure(it) },
                    torch = torch,
                    onToggleTorch = { vm.toggleTorch() },
                    timerSeconds = config.timerSeconds,
                    onTimer = { vm.setTimer(it) },
                    grid = grid,
                    onToggleGrid = { vm.toggleGrid() },
                )
            }
        }

        photoThumb?.let { thumb ->
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Ostatnie zdjęcie",
                    modifier = Modifier.size(96.dp),
                )
                Text(
                    saveMsg ?: if (photoSaved) "Zapisano ✓" else "Zrobiono ✓",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            LaunchedEffect(thumb) { delay(2500); vm.clearPhotoThumb() }
        }
    }

    if (showSettings) {
        SettingsSheet(
            config = config,
            onChange = { vm.updateConfig(it) },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun ConnectPanel(
    status: SessionStatus,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onScan: () -> Unit,
    onDiscover: () -> Unit,
    discovered: Pair<String, Int>?,
    onConnectDiscovered: () -> Unit,
    qrError: String?,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Połącz z telefonem-kamerą", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Zeskanuj kod QR (hotspot, bez routera)")
        }
        qrError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Text("— albo ta sama sieć Wi-Fi —", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualIp,
            onValueChange = onManualIpChange,
            label = { Text("IP telefonu-kamery") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onConnectManual,
            enabled = manualIp.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Połącz po IP") }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
            Text("Szukaj w sieci (NSD)")
        }
        discovered?.let {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnectDiscovered, modifier = Modifier.fillMaxWidth()) {
                Text("Połącz: ${it.first}:${it.second}")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(sessionStatusText(status), color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    config: StreamConfig,
    onChange: (StreamConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Ustawienia podglądu", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            Text("Rozdzielczość: ${config.analysisHeight}p")
            Row {
                FilterChip(
                    selected = config.analysisHeight == 480,
                    onClick = { onChange(config.copy(analysisHeight = 480)) },
                    label = { Text("480p") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = config.analysisHeight == 720,
                    onClick = { onChange(config.copy(analysisHeight = 720)) },
                    label = { Text("720p") },
                )
            }
            Spacer(Modifier.height(16.dp))

            Text("Płynność: ${config.fps} FPS")
            Slider(
                value = config.fps.toFloat(),
                onValueChange = { onChange(config.copy(fps = it.roundToInt())) },
                valueRange = 5f..30f,
            )
            Spacer(Modifier.height(16.dp))

            Text("Jakość JPEG: ${config.jpegQuality}")
            Slider(
                value = config.jpegQuality.toFloat(),
                onValueChange = { onChange(config.copy(jpegQuality = it.roundToInt())) },
                valueRange = 30f..90f,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zapisuj zdjęcia też na tym telefonie")
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.saveToViewer,
                    onCheckedChange = { onChange(config.copy(saveToViewer = it)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Zmiana rozdzielczości na chwilę zatrzyma podgląd (przeładowanie kamery).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
