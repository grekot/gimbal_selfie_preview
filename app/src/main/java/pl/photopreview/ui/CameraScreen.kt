package pl.photopreview.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.photopreview.Prefs
import pl.photopreview.SessionStatus
import pl.photopreview.camera.CameraController
import pl.photopreview.gimbal.GimbalController
import pl.photopreview.input.ShutterKeyBus
import pl.photopreview.net.JoinPayload
import pl.photopreview.service.StreamingService
import pl.photopreview.vm.CameraViewModel
import pl.photopreview.wifi.HotspotInfo

private const val CAM_PANEL_NONE = 0
private const val CAM_PANEL_ZOOM = 1
private const val CAM_PANEL_ADV = 2

@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: CameraViewModel = viewModel()
    val scope = rememberCoroutineScope()
    ImmersiveFullScreen()
    KeepScreenOn()

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

    var hasAudio by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasAudio = it }

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

    var showQr by remember { mutableStateOf(false) }
    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startHotspot() }

    val controller = remember(lifecycleOwner) { CameraController(context, lifecycleOwner) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val shutterSound = remember { MediaActionSound() }
    DisposableEffect(Unit) {
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        onDispose { shutterSound.release() }
    }

    val prefs = remember { Prefs(context) }
    val gimbal = remember { GimbalController(context) }
    var gimbalStatus by remember { mutableStateOf("Gimbal: rozłączony") }
    DisposableEffect(gimbal) {
        gimbal.onState = { gimbalStatus = it }
        onDispose { gimbal.close() }
    }
    val btConnectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) gimbal.connect(prefs.gimbalMac ?: GimbalController.DEFAULT_MAC) }
    fun connectGimbal() {
        val mac = prefs.gimbalMac ?: GimbalController.DEFAULT_MAC
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            btConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            gimbal.connect(mac)
        }
    }

    var savedMsg by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var ev by remember { mutableFloatStateOf(0f) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomMin by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFlash by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastIsVideo by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    var openPanel by remember { mutableIntStateOf(CAM_PANEL_NONE) }
    val zoomRatioLatest by rememberUpdatedState(zoomRatio)
    val zoomMinLatest by rememberUpdatedState(zoomMin)
    val zoomMaxLatest by rememberUpdatedState(zoomMax)

    val captureOne: (() -> Unit) -> Unit = { onComplete ->
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        showFlash = true
        controller.takePhoto { uri ->
            savedMsg = if (uri != null) "Zdjęcie zapisane ✓" else "Błąd zapisu zdjęcia"
            if (uri != null) {
                lastUri = uri
                lastIsVideo = false
                scope.launch(Dispatchers.IO) {
                    val thumb = controller.thumbnailFor(uri)
                    if (thumb != null && thumb.isNotEmpty()) {
                        lastThumb = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                    }
                    if (vm.config.value.saveToViewer) {
                        val full = controller.fullBytesFor(uri)
                        if (full != null) vm.session.sendPhotoFull(full)
                        else vm.session.sendPhotoTaken(thumb ?: ByteArray(0))
                    } else {
                        vm.session.sendPhotoTaken(thumb ?: ByteArray(0))
                    }
                }
            }
            onComplete()
        }
    }
    val doCapture: () -> Unit = {
        val count = if (vm.config.value.burst) 3 else 1
        val strong = vm.config.value.strongFlash
        scope.launch {
            if (strong) {
                controller.setTorch(true)
                delay(450) // let exposure settle under the LED before the first shot
            }
            for (i in 1..count) {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                captureOne { done.complete(Unit) }
                done.await()
                if (i < count) delay(250)
            }
            if (strong) controller.setTorch(torch) // restore the torch toggle state
        }
    }
    val shoot: () -> Unit = {
        if (vm.config.value.videoMode) {
            if (controller.isRecording()) controller.stopRecording() else controller.startRecording(hasAudio)
        } else {
            val timer = vm.config.value.timerSeconds
            if (timer <= 0) {
                doCapture()
            } else {
                scope.launch {
                    for (s in timer downTo 1) {
                        countdown = s
                        vm.session.sendCountdown(s)
                        delay(1000)
                    }
                    countdown = null
                    vm.session.sendCountdown(0)
                    doCapture()
                }
            }
        }
    }

    DisposableEffect(controller) {
        controller.onFrame = { jpeg, rot -> vm.session.submitFrame(jpeg, rot) }
        controller.onVideoConfig = { vc -> vm.session.setVideoConfig(vc.toPayload()) }
        controller.onVideoFrame = { nal, key -> vm.session.submitVideo(nal, key) }
        controller.onRecordingState = { rec -> recording = rec; vm.session.sendRecState(rec) }
        controller.onVideoSaved = { uri -> if (uri != null) { lastUri = uri; lastIsVideo = true } }
        vm.session.onShutter = shoot
        vm.session.onZoom = { r -> zoomRatio = r; controller.setZoomRatio(r) }
        vm.session.onExposure = { e -> ev = e; controller.setExposureFraction(e) }
        vm.session.onTorch = { t -> torch = t; controller.setTorch(t) }
        vm.session.onFocus = { ux, uy -> controller.focusNormalized(ux, uy) }
        vm.session.onFocusReset = { controller.resetFocus() }
        vm.session.onGimbal = { pan, tilt, roll ->
            when {
                roll != 0 -> gimbal.startRoll(roll)
                pan == 0 && tilt == 0 -> gimbal.stopMove()
                else -> gimbal.startMove(pan, tilt)
            }
        }
        ShutterKeyBus.onShutter = shoot
        ShutterKeyBus.onZoomIn = {
            val nz = (zoomRatio * 1.25f).coerceIn(zoomMin, zoomMax)
            zoomRatio = nz; controller.setZoomRatio(nz)
        }
        ShutterKeyBus.onZoomOut = {
            val nz = (zoomRatio / 1.25f).coerceIn(zoomMin, zoomMax)
            zoomRatio = nz; controller.setZoomRatio(nz)
        }
        StreamingService.start(context, "Tryb kamery – transmisja podglądu")

        onDispose {
            ShutterKeyBus.onShutter = null
            ShutterKeyBus.onZoomIn = null
            ShutterKeyBus.onZoomOut = null
            vm.session.onShutter = null
            vm.session.onZoom = null
            vm.session.onExposure = null
            vm.session.onTorch = null
            vm.session.onFocus = null
            vm.session.onFocusReset = null
            vm.session.onGimbal = null
            controller.onRecordingState = null
            controller.onVideoSaved = null
            controller.unbind()
            StreamingService.stop(context)
            vm.stopHotspot()
        }
    }

    LaunchedEffect(config.jpegQuality) { controller.jpegQuality = config.jpegQuality }
    LaunchedEffect(config.fps) { controller.frameRate = config.fps }
    LaunchedEffect(flashMode, config.strongFlash) {
        controller.captureFlashMode = if (config.strongFlash) ImageCapture.FLASH_MODE_OFF else flashMode
    }
    LaunchedEffect(config.useH264) {
        controller.useH264 = config.useH264
        controller.resetEncoder()
    }
    LaunchedEffect(hasCamera, config.analysisHeight, config.videoMode, config.frontCamera) {
        if (hasCamera) {
            controller.videoMode = config.videoMode
            controller.frontCamera = config.frontCamera
            runCatching { controller.bind(previewView, config.analysisHeight) }
            val (mn, mx) = controller.zoomRange()
            zoomMin = mn
            zoomMax = mx
            zoomRatio = zoomRatio.coerceIn(mn, mx)
            vm.session.sendZoomRange(mn, mx)
        }
    }
    LaunchedEffect(savedMsg) { if (savedMsg != null) { delay(1800); savedMsg = null } }
    LaunchedEffect(showFlash) { if (showFlash) { delay(90); showFlash = false } }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(700); focusPoint = null } }
    LaunchedEffect(recording) {
        if (recording) {
            recSeconds = 0
            while (true) { delay(1000); recSeconds++ }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            val nz = (zoomRatioLatest * gestureZoom).coerceIn(zoomMinLatest, zoomMaxLatest)
                            zoomRatio = nz
                            controller.setZoomRatio(nz)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { controller.resetFocus() },
                            onTap = { offset ->
                                focusPoint = offset
                                runCatching {
                                    controller.focus(previewView.meteringPointFactory.createPoint(offset.x, offset.y))
                                }
                            },
                        )
                    },
            )
            if (grid) GridOverlay(Modifier.fillMaxSize())
            focusPoint?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = 36.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
                }
            }
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

        if (showFlash) {
            Box(Modifier.fillMaxSize().background(Color.White))
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .statusBarsPadding().background(Color(0x88000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Wstecz", color = Color.White) }
            Spacer(Modifier.width(8.dp))
            val hi = hotspotInfo
            val infoText = when {
                status is SessionStatus.Connected -> sessionStatusText(status)
                hi != null -> "Hotspot: ${hi.ssid} / ${hi.passphrase}"
                else -> "IP: " + vm.localAddresses.joinToString().ifEmpty { "—" } + " :${vm.port}"
            }
            Text(infoText, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    Manifest.permission.ACCESS_FINE_LOCATION
                }
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    vm.startHotspot()
                } else {
                    wifiPermLauncher.launch(perm)
                }
                showQr = true
            }) { Text("Hotspot/QR", color = Color.White) }
        }

        Column(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 44.dp, start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (recording) Text("● $recSeconds s", color = Color.Red, style = MaterialTheme.typography.titleMedium)
            if (kotlin.math.abs(zoomRatio - 1f) > 0.05f) {
                val zoomLabel = String.format(java.util.Locale.US, "%.1f", zoomRatio).removeSuffix(".0") + "x"
                Surface(color = Color(0x88000000), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        zoomLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        lastThumb?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Ostatnie",
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 44.dp, end = 10.dp).size(56.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        lastUri?.let { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, if (lastIsVideo) "video/*" else "image/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        }
                    },
            )
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

        if (hasCamera) {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding()) {
                if (openPanel == CAM_PANEL_ZOOM) {
                    Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        ZoomControls(
                            zoomRatio = zoomRatio,
                            zoomMin = zoomMin,
                            zoomMax = zoomMax,
                            onZoomRatio = { zoomRatio = it; controller.setZoomRatio(it) },
                        )
                    }
                } else if (openPanel == CAM_PANEL_ADV) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0x99000000))
                            .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !config.videoMode,
                                onClick = { if (!controller.isRecording()) vm.setVideoMode(false) },
                                label = { Text("Foto") },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = config.videoMode,
                                onClick = {
                                    if (!hasAudio) audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    vm.setVideoMode(true)
                                },
                                label = { Text("Wideo") },
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.setFrontCamera(!config.frontCamera) }) {
                                Text(if (config.frontCamera) "Przód" else "Tył")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                gimbalStatus,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { connectGimbal() }) { Text("Gimbal") }
                            TextButton(onClick = {
                                gimbal.disconnect(); gimbalStatus = "Gimbal: rozłączony"
                            }) { Text("Rozłącz") }
                        }
                        if (!config.videoMode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Lampa zdjęcia:", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(8.dp))
                                listOf(
                                    ImageCapture.FLASH_MODE_OFF to "Wył",
                                    ImageCapture.FLASH_MODE_AUTO to "Auto",
                                    ImageCapture.FLASH_MODE_ON to "Wł",
                                ).forEach { (mode, lbl) ->
                                    FilterChip(
                                        selected = flashMode == mode,
                                        onClick = { flashMode = mode },
                                        label = { Text(lbl) },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tryb seryjny (3 zdjęcia)", color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = config.burst, onCheckedChange = { vm.setBurst(it) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Mocna lampa (ciągłe światło)", color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = config.strongFlash, onCheckedChange = { vm.setStrongFlash(it) })
                            }
                        }
                        CommonAdvancedControls(
                            exposure = ev,
                            onExposure = { ev = it; controller.setExposureFraction(it) },
                            torch = torch,
                            onToggleTorch = { torch = !torch; controller.setTorch(torch) },
                            timerSeconds = config.timerSeconds,
                            onTimer = { vm.setTimer(it) },
                            grid = grid,
                            onToggleGrid = { grid = !grid },
                            onResetFocus = { controller.resetFocus() },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { openPanel = if (openPanel == CAM_PANEL_ZOOM) CAM_PANEL_NONE else CAM_PANEL_ZOOM }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom", tint = Color.White)
                    }
                    if (config.videoMode) {
                        Box(
                            Modifier.size(72.dp)
                                .border(3.dp, Color.White, CircleShape)
                                .padding(if (recording) 20.dp else 6.dp)
                                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                                .background(Color.Red)
                                .clickable { shoot() },
                        )
                    } else {
                        RoundShutterButton(onClick = shoot)
                    }
                    IconButton(onClick = { openPanel = if (openPanel == CAM_PANEL_ADV) CAM_PANEL_NONE else CAM_PANEL_ADV }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
                    }
                }
            }
        }
    }

    if (showQr) {
        HotspotQrDialog(
            info = hotspotInfo,
            error = hotspotError,
            port = vm.port,
            onClose = { showQr = false },
            onStop = { showQr = false; vm.stopHotspot() },
        )
    }
}

@Composable
private fun HotspotQrDialog(
    info: HotspotInfo?,
    error: String?,
    port: Int,
    onClose: () -> Unit,
    onStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Zamknij (hotspot działa dalej)") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Zatrzymaj") } },
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
                        Text("Android 10+: zeskanuj w trybie Podgląd.")
                        Spacer(Modifier.height(8.dp))
                        QrImage(
                            JoinPayload.build(info.ssid, info.passphrase, port),
                            modifier = Modifier.size(220.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("SSID: ${info.ssid}", style = MaterialTheme.typography.titleMedium)
                        Text("Hasło: ${info.passphrase}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Hasło jest losowe (Android nie pozwala go skrócić). Na Androidzie 10+ " +
                                "zeskanuj QR. Starszy telefon: połącz się z tą siecią ręcznie w Ustawieniach " +
                                "Wi-Fi, potem w Podglądzie naciśnij Szukaj w sieci (NSD). Hotspot działa po " +
                                "zamknięciu okna — sparuj, potem Zatrzymaj.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}
