package pl.photopreview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * Compact shooting controls: always-visible row (zoom presets + torch + "more"); the sliders
 * (zoom, EV with reset), grid and timer live in an on-demand "advanced" section so they don't
 * cover the preview.
 */
@Composable
fun ShootingControls(
    zoomRatio: Float,
    zoomMin: Float,
    zoomMax: Float,
    onZoomRatio: (Float) -> Unit,
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
    var expanded by remember { mutableStateOf(false) }
    val hasZoom = zoomMax > zoomMin + 0.01f
    val evPct = (exposure * 100).toInt()

    Column(modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Always-visible compact row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasZoom) {
                listOf(1f, 2f, 3f, 5f).filter { it >= zoomMin && it <= zoomMax }.forEach { r ->
                    FilterChip(
                        selected = abs(zoomRatio - r) < 0.15f,
                        onClick = { onZoomRatio(r) },
                        label = { Text("${r.toInt()}x") },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = torch, onClick = onToggleTorch, label = { Text("Lampa") })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Mniej" else "Więcej", color = Color.White)
            }
        }

        // On-demand advanced section (preview stays visible above this panel).
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            if (hasZoom) {
                Text(
                    "Zoom: " + String.format(Locale.US, "%.1fx", zoomRatio),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = zoomRatio.coerceIn(zoomMin, zoomMax),
                    onValueChange = onZoomRatio,
                    valueRange = zoomMin..zoomMax,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Jasność (EV): " + (if (evPct > 0) "+$evPct%" else "$evPct%"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onExposure(0f) }) { Text("Reset", color = Color.White) }
            }
            Slider(value = exposure, onValueChange = onExposure, valueRange = -1f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
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
}

/** Native-style round shutter button. */
@Composable
fun RoundShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(72.dp)
            .border(3.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
    )
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
