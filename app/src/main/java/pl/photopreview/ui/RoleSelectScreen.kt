package pl.photopreview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RoleSelectScreen(onCamera: () -> Unit, onViewer: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
    }
}
