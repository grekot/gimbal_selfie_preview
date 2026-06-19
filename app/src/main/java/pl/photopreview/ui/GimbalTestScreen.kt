package pl.photopreview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import pl.photopreview.Prefs
import pl.photopreview.gimbal.GimbalController
import kotlin.math.roundToInt

/**
 * Diagnostic/calibration screen (camera phone): connect to the gimbal over BLE and drive pan/tilt
 * with four hold-buttons, so we can confirm movement and learn which axis/sign = which direction.
 */
@Composable
fun GimbalTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var mac by remember { mutableStateOf(prefs.gimbalMac ?: GimbalController.DEFAULT_MAC) }
    var status by remember { mutableStateOf("Rozłączony") }
    var info by remember { mutableStateOf("") }
    var speed by remember { mutableIntStateOf(45) }
    var cmdHex by remember { mutableStateOf("") }
    var payloadHex by remember { mutableStateOf("") }
    var telemetry by remember { mutableStateOf("") }

    val controller = remember {
        GimbalController(context).apply {
            onState = { status = it }
            onInfo = { info = it }
            onTelemetry = { telemetry = it }
        }
    }
    DisposableEffect(Unit) { onDispose { controller.close() } }

    fun doConnect() {
        prefs.gimbalMac = mac
        controller.connect(mac)
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) doConnect() else status = "Brak uprawnienia Bluetooth" }

    fun connectWithPerm() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            doConnect()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = onBack) { Text("‹ Wstecz") }
        Text("Sterowanie gimbalem (test)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Najpierw ZAMKNIJ oryginalną apkę Aochuan (gimbal łączy się tylko z jedną apką). " +
                "Połącz, potem trzymaj przyciski i zanotuj, w którą stronę faktycznie rusza się gimbal.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it },
            label = { Text("Adres MAC gimbala") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { connectWithPerm() }) { Text("Połącz") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { controller.disconnect(); status = "Rozłączony" }) { Text("Rozłącz") }
        }

        Spacer(Modifier.height(12.dp))
        Text("Status: $status", style = MaterialTheme.typography.titleMedium)
        if (info.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(info, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
        }

        Spacer(Modifier.height(16.dp))
        Text("Prędkość: $speed", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = speed.toFloat(),
            onValueChange = { speed = it.roundToInt() },
            valueRange = 10f..150f,
        )

        Spacer(Modifier.height(8.dp))
        // D-pad. Labels show what is SENT (P=pan, T=tilt, +/- sign) for calibration.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // Calibrated on-device: pan +/- = right/left (OK), tilt sign inverted vs arrow → flipped here.
            HoldButton("▲\ngóra", controller, panSign = 0, tiltSign = -1, speed = speed)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoldButton("◀\nlewo", controller, panSign = -1, tiltSign = 0, speed = speed)
                Spacer(Modifier.width(84.dp))
                HoldButton("▶\nprawo", controller, panSign = 1, tiltSign = 0, speed = speed)
            }
            HoldButton("▼\ndół", controller, panSign = 0, tiltSign = 1, speed = speed)
        }

        Spacer(Modifier.height(20.dp))
        Text("Sonda BLE (eksperyment — szukanie POV)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Wyślij dowolną komendę (apka dolicza długość i sumę). Próbuj kodów 80xx pojedynczo, " +
                "obserwując gimbal i telemetrię. UWAGA: nieznane kody mogą wywołać kalibrację/reset — " +
                "trzymaj palec na wyłączniku gimbala.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmdHex,
                onValueChange = { cmdHex = it.trim() },
                label = { Text("cmd hex (np. 8018)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = payloadHex,
                onValueChange = { payloadHex = it.trim() },
                label = { Text("dane hex") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            val cmd = cmdHex.toIntOrNull(16)
            val payload = runCatching {
                if (payloadHex.isEmpty()) {
                    ByteArray(0)
                } else {
                    ByteArray(payloadHex.length / 2) {
                        payloadHex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                    }
                }
            }.getOrNull()
            if (cmd != null && payload != null) controller.sendCommand(cmd, payload)
        }) { Text("Wyślij komendę") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Telemetria: ${telemetry.ifEmpty { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF80CBC4),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Gdy potwierdzimy, który przycisk = który kierunek, podepnę joystick na ekranie Podglądu " +
                "(komenda poleci po Wi-Fi do telefonu-kamery, a ten wyśle ją po BLE do gimbala).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HoldButton(
    label: String,
    controller: GimbalController,
    panSign: Int,
    tiltSign: Int,
    speed: Int,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(6.dp)
            .size(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) Color(0xFF1E88E5) else Color(0xFF455A64))
            .pointerInput(speed, panSign, tiltSign) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        controller.startMove(panSign * speed, tiltSign * speed)
                        tryAwaitRelease()
                        controller.stopMove()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
