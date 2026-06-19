package pl.photopreview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.photopreview.SessionStatus
import pl.photopreview.vm.ViewerViewModel
import kotlin.math.roundToInt

private const val PANEL_NONE = 0
private const val PANEL_ZOOM = 1
private const val PANEL_ADV = 2

@Composable
fun ViewerScreen(onBack: () -> Unit) {
    val vm: ViewerViewModel = viewModel()
    ImmersiveFullScreen()
    KeepScreenOn()
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
    val zoomRange by vm.zoomRange.collectAsState()
    val exposure by vm.exposure.collectAsState()
    val torch by vm.torch.collectAsState()
    val grid by vm.grid.collectAsState()
    val videoConfig by vm.videoConfig.collectAsState()
    val recording by vm.recording.collectAsState()
    val battery by vm.battery.collectAsState()
    val zoomLatest by rememberUpdatedState(zoom)
    val zoomRangeLatest by rememberUpdatedState(zoomRange)
    val frameLatest by rememberUpdatedState(frame)
    val videoConfigLatest by rememberUpdatedState(videoConfig)
    val useH264Latest by rememberUpdatedState(config.useH264)
    var focusTap by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusTap) { if (focusTap != null) { delay(700); focusTap = null } }
    val scope = rememberCoroutineScope()
    var showGimbal by remember { mutableStateOf(false) }

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
    var openPanel by remember { mutableIntStateOf(PANEL_NONE) }
    LaunchedEffect(Unit) { if (vm.lastHost != null) vm.reconnect() }

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
            if (now - last >= 1000) { fps = count; count = 0; last = now }
        }
    }

    val df = frame
    val connected = status is SessionStatus.Connected
    val showH264 = config.useH264 && connected
    val showJpeg = !config.useH264 && df != null
    val previewActive = showH264 || showJpeg

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (previewActive) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            val (mn, mx) = zoomRangeLatest
                            vm.setZoom((zoomLatest * gestureZoom).coerceIn(mn, mx))
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { vm.resetFocus() },
                            onTap = { offset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val vc = videoConfigLatest
                                val f = frameLatest
                                val dims = if (useH264Latest && vc != null) {
                                    Triple(vc.width.toFloat(), vc.height.toFloat(), vc.rotation)
                                } else if (f != null) {
                                    Triple(f.bitmap.width.toFloat(), f.bitmap.height.toFloat(), f.rotationDegrees)
                                } else {
                                    Triple(0f, 0f, 0)
                                }
                                val nw = dims.first
                                val nh = dims.second
                                val rot = dims.third
                                if (nw > 0f && nh > 0f && w > 0f && h > 0f) {
                                    val rotated = rot % 180 != 0
                                    val cw = if (rotated) nh else nw
                                    val ch = if (rotated) nw else nh
                                    val scale = minOf(w / cw, h / ch)
                                    val dispW = cw * scale
                                    val dispH = ch * scale
                                    val left = (w - dispW) / 2f
                                    val top = (h - dispH) / 2f
                                    if (offset.x in left..(left + dispW) && offset.y in top..(top + dispH)) {
                                        vm.sendFocus(
                                            ((offset.x - left) / dispW).coerceIn(0f, 1f),
                                            ((offset.y - top) / dispH).coerceIn(0f, 1f),
                                        )
                                        focusTap = offset
                                    }
                                }
                            },
                        )
                    },
            ) {
                if (showH264) {
                    H264PreviewView(
                        videoConfig = videoConfig,
                        registerSink = vm::setVideoSink,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (df != null) {
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
                }
                if (grid) GridOverlay(Modifier.fillMaxSize())
                focusTap?.let { p ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, radius = 32.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        } else {
            ConnectPanel(
                status = status,
                manualIp = manualIp,
                onManualIpChange = { manualIp = it },
                onConnectManual = { vm.connectManual(manualIp) },
                onScan = {
                    scanLauncher.launch(
                        ScanOptions().setOrientationLocked(false).setBeepEnabled(false)
                            .setPrompt("Zeskanuj kod QR z telefonu-kamery"),
                    )
                },
                onDiscover = { vm.startDiscovery() },
                discovered = discovered,
                onConnectDiscovered = { vm.connectDiscovered() },
                qrError = qrError,
                lastHost = vm.lastHost,
                onReconnect = { vm.reconnect() },
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .statusBarsPadding().background(Color(0x88000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Wstecz", color = Color.White) }
            Spacer(Modifier.weight(1f))
            val tag = when {
                showH264 -> "  •  H.264"
                df != null -> "  •  $fps fps"
                else -> ""
            }
            Text(
                sessionStatusText(status) + tag,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            battery?.let {
                Spacer(Modifier.width(8.dp))
                Text("🔋 $it%", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (recording) {
            Text(
                "● REC",
                color = Color.Red,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            )
        }

        if (previewActive && kotlin.math.abs(zoom - 1f) > 0.05f) {
            val zoomLabel = String.format(java.util.Locale.US, "%.1f", zoom).removeSuffix(".0") + "x"
            Surface(
                color = Color(0x88000000),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 44.dp, start = 10.dp),
            ) {
                Text(
                    zoomLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
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

        if (previewActive) {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding()) {
                if (openPanel == PANEL_ZOOM) {
                    Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        ZoomControls(
                            zoomRatio = zoom,
                            zoomMin = zoomRange.first,
                            zoomMax = zoomRange.second,
                            onZoomRatio = { vm.setZoom(it) },
                        )
                    }
                } else if (openPanel == PANEL_ADV) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0x99000000))
                            .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !config.videoMode,
                                onClick = { vm.setVideoMode(false) },
                                label = { Text("Foto") },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = config.videoMode,
                                onClick = { vm.setVideoMode(true) },
                                label = { Text("Wideo") },
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.setFrontCamera(!config.frontCamera) }) {
                                Text(if (config.frontCamera) "Przód" else "Tył")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        CommonAdvancedControls(
                            exposure = exposure,
                            onExposure = { vm.setExposure(it) },
                            torch = torch,
                            onToggleTorch = { vm.toggleTorch() },
                            timerSeconds = config.timerSeconds,
                            onTimer = { vm.setTimer(it) },
                            grid = grid,
                            onToggleGrid = { vm.toggleGrid() },
                            onResetFocus = { vm.resetFocus() },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Strumień", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = config.analysisHeight == 480,
                                onClick = { vm.updateConfig(config.copy(analysisHeight = 480)) },
                                label = { Text("480p") },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = config.analysisHeight == 720,
                                onClick = { vm.updateConfig(config.copy(analysisHeight = 720)) },
                                label = { Text("720p") },
                            )
                        }
                        Text("Płynność: ${config.fps} FPS", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = config.fps.toFloat(),
                            onValueChange = { vm.updateConfig(config.copy(fps = it.roundToInt())) },
                            valueRange = 5f..30f,
                        )
                        Text("Jakość JPEG: ${config.jpegQuality}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = config.jpegQuality.toFloat(),
                            onValueChange = { vm.updateConfig(config.copy(jpegQuality = it.roundToInt())) },
                            valueRange = 30f..90f,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Płynny obraz (H.264)", color = Color.White, modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.useH264,
                                onCheckedChange = { vm.updateConfig(config.copy(useH264 = it)) },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zapisuj zdjęcia też tutaj", color = Color.White, modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.saveToViewer,
                                onCheckedChange = { vm.updateConfig(config.copy(saveToViewer = it)) },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { openPanel = if (openPanel == PANEL_ZOOM) PANEL_NONE else PANEL_ZOOM }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom", tint = Color.White)
                    }
                    RoundShutterButton(onClick = { vm.sendShutter() })
                    IconButton(onClick = { openPanel = if (openPanel == PANEL_ADV) PANEL_NONE else PANEL_ADV }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
                    }
                }
            }
        }

        if (previewActive) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                if (showGimbal) {
                    GimbalPad(
                        scope = scope,
                        onMove = { pan, tilt, roll -> vm.sendGimbal(pan, tilt, roll) },
                        onStop = { vm.sendGimbal(0, 0, 0) },
                        onClose = { showGimbal = false },
                    )
                } else {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { showGimbal = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("🕹", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                }
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
    lastHost: String?,
    onReconnect: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Połącz z telefonem-kamerą", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        lastHost?.let { h ->
            Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Połącz ponownie: $h")
            }
            Spacer(Modifier.height(12.dp))
        }
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

@Composable
private fun GimbalPad(
    scope: CoroutineScope,
    onMove: (Int, Int, Int) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    var speed by remember { mutableIntStateOf(45) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(200.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).padding(6.dp),
    ) {
        GimbalArrow("▲", 0, -speed, 0, scope, onMove, onStop)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GimbalArrow("◀", -speed, 0, 0, scope, onMove, onStop)
            Box(
                Modifier.size(56.dp).clip(CircleShape).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium) }
            GimbalArrow("▶", speed, 0, 0, scope, onMove, onStop)
        }
        GimbalArrow("▼", 0, speed, 0, scope, onMove, onStop)
        Spacer(Modifier.height(8.dp))
        listOf(25 to "Wolno", 45 to "Średnio", 80 to "Szybko").forEach { (v, lbl) ->
            Box(
                Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (speed == v) Color(0xFF1E88E5) else Color(0xFF37474F))
                    .clickable { speed = v }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(lbl, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun GimbalArrow(
    label: String,
    pan: Int,
    tilt: Int,
    roll: Int,
    scope: CoroutineScope,
    onMove: (Int, Int, Int) -> Unit,
    onStop: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(4.dp)
            .size(56.dp)
            .clip(CircleShape)
            .background(if (pressed) Color(0xFF1E88E5) else Color(0xFF455A64))
            .pointerInput(pan, tilt, roll) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val job = scope.launch {
                            while (isActive) {
                                onMove(pan, tilt, roll)
                                delay(150)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                        onStop()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}
