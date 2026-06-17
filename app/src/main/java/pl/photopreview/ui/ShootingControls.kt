package pl.photopreview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared shooting controls used by both the camera screen and the viewer screen. */
@Composable
fun ShootingControls(
    zoom: Float,
    onZoom: (Float) -> Unit,
    exposure: Float,
    onExposure: (Float) -> Unit,
    torch: Boolean,
    onToggleTorch: () -> Unit,
    timerSeconds: Int,
    onTimer: (Int) -> Unit,
    grid: Boolean,
    onToggleGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val evPct = (exposure * 100).toInt()
    Column(modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Zoom: ${(zoom * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(value = zoom, onValueChange = onZoom, valueRange = 0f..1f)

        Text(
            "Jasność (EV): " + (if (evPct > 0) "+$evPct%" else "$evPct%"),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(value = exposure, onValueChange = onExposure, valueRange = -1f..1f)

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = torch, onClick = onToggleTorch, label = { Text("Lampa") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = grid, onClick = onToggleGrid, label = { Text("Siatka") })
            Spacer(Modifier.width(16.dp))
            Text("Timer:", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            listOf(0, 3, 10).forEach { s ->
                FilterChip(
                    selected = timerSeconds == s,
                    onClick = { onTimer(s) },
                    label = { Text(if (s == 0) "Off" else "${s}s") },
                )
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** Rule-of-thirds composition grid drawn over the preview. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val line = Color(0x66FFFFFF)
        val sw = 1.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(line, Offset(w / 3f, 0f), Offset(w / 3f, h), sw)
        drawLine(line, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), sw)
        drawLine(line, Offset(0f, h / 3f), Offset(w, h / 3f), sw)
        drawLine(line, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), sw)
    }
}
