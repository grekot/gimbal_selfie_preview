package pl.photopreview.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.photopreview.input.ShutterKeyBus

/** Diagnostic: shows the key codes the (Bluetooth HID) gimbal remote sends, so they can be mapped. */
@Composable
fun RemoteScanScreen(onBack: () -> Unit) {
    val counts = remember { mutableStateMapOf<Int, Int>() }
    var lastCode by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        ShutterKeyBus.onKeyDebug = { code ->
            counts[code] = (counts[code] ?: 0) + 1
            lastCode = code
        }
        onDispose { ShutterKeyBus.onKeyDebug = null }
    }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Wstecz") }
        Spacer(Modifier.height(8.dp))
        Text("Skaner pilota", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "1) Sparuj pilota z TYM telefonem przez Bluetooth (ten sam, który działa w aparacie). " +
                "2) Naciskaj po kolei przyciski pilota — pojawią się ich kody. " +
                "3) Zapisz, który przycisk daje który kod, i podaj mi je (np. zoom +, zoom −, spust).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        lastCode?.let {
            Text(
                "Ostatni: $it  (${KeyEvent.keyCodeToString(it)})",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (counts.isEmpty()) {
            Text("Naciśnij przycisk na pilocie…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Wykryte kody:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            counts.entries.sortedBy { it.key }.forEach { (code, n) ->
                Text(
                    "•  $code  —  ${KeyEvent.keyCodeToString(code)}   (×$n)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { counts.clear(); lastCode = null }) { Text("Wyczyść") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Jeśli któryś przycisk nie pokazuje żadnego kodu — system nie przekazuje go do aplikacji " +
                "(takiego nie da się przechwycić). Wtedy daj znać, poszukamy innej drogi.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
