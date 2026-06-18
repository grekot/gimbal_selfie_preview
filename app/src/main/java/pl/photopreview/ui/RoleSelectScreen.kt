package pl.photopreview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.photopreview.BuildConfig
import pl.photopreview.update.Updater

@Composable
fun RoleSelectScreen(onCamera: () -> Unit, onViewer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var latest by remember { mutableStateOf<Updater.ReleaseInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    fun check(auto: Boolean) {
        if (checking || downloading) return
        checking = true
        msg = null
        scope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            checking = false
            if (rel?.apkUrl != null && Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)) {
                latest = rel
                showDialog = true
            } else if (!auto) {
                msg = if (rel == null) {
                    "Nie udało się sprawdzić (sieć albo repo prywatne)."
                } else {
                    "Masz najnowszą wersję (${BuildConfig.VERSION_NAME})."
                }
            }
        }
    }

    fun doUpdate() {
        val url = latest?.apkUrl ?: return
        if (!Updater.canInstall(context)) {
            updateError = "Zezwól tej aplikacji na instalowanie aplikacji, potem spróbuj ponownie."
            Updater.openInstallPermission(context)
            return
        }
        downloading = true
        progress = 0
        updateError = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { Updater.downloadApk(context, url) { p -> progress = p } }
                downloading = false
                Updater.installApk(context, file)
            } catch (e: Exception) {
                downloading = false
                updateError = e.message ?: "Błąd aktualizacji"
            }
        }
    }

    LaunchedEffect(Unit) { check(auto = true) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Podgląd Kadru", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Wybierz rolę tego telefonu",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onCamera,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text("KAMERA  (telefon na gimbalu)") }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onViewer,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text("PODGLĄD  (telefon w ręce)") }
        Spacer(Modifier.height(24.dp))
        Text(
            "Telefon-kamera transmituje obraz na żywo; telefon-podgląd pokazuje kadr " +
                "i może zdalnie wyzwolić zdjęcie. Spust działa też z pilota gimbala.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text("Wersja: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { check(auto = false) }) {
            Text(if (checking) "Sprawdzam…" else "Sprawdź aktualizacje")
        }
        msg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }

    if (showDialog) {
        val rel = latest
        AlertDialog(
            onDismissRequest = { if (!downloading) showDialog = false },
            confirmButton = {
                TextButton(onClick = { doUpdate() }, enabled = !downloading) {
                    Text(if (downloading) "Pobieranie… $progress%" else "Pobierz i zainstaluj")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }, enabled = !downloading) { Text("Później") }
            },
            title = { Text("Aktualizacja: ${rel?.version ?: ""}") },
            text = {
                Column {
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Masz wersję ${BuildConfig.VERSION_NAME}.")
                    val notes = rel?.notes?.trim().orEmpty()
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notes.take(600), style = MaterialTheme.typography.bodySmall)
                    }
                    updateError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Błąd: $it", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }
}
