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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/** Zoom panel (shown on demand behind the loupe button). Presets + fine slider. */
@Composable
fun ZoomControls(
    zoomRatio: Float,
    zoomMin: Float,
    zoomMax: Float,
    onZoomRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Zoom: " + String.format(Locale.US, "%.1fx", zoomRatio),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(1f, 2f, 3f, 5f).filter { it >= zoomMin && it <= zoomMax }.forEach { r ->
                FilterChip(
                    selected = abs(zoomRatio - r) < 0.15f,
                    onClick = { onZoomRatio(r) },
                    label = { Text("${r.toInt()}x") },
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        if (zoomMax > zoomMin + 0.01f) {
            Slider(
                value = zoomRatio.coerceIn(zoomMin, zoomMax),
                onValueChange = onZoomRatio,
                valueRange = zoomMin..zoomMax,
            )
        }
    }
}

/** Controls common to both screens, shown on demand behind the gear button. */
@Composable
fun CommonAdvancedControls(
    exposure: Float,
    onExposure: (Float) -> Unit,
    torch: Boolean,
    onToggleTorch: () -> Unit,
    timerSeconds: Int,
    onTimer: (Int) -> Unit,
    grid: Boolean,
    onToggleGrid: () -> Unit,
    onResetFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val evPct = (exposure * 100).toInt()
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Jasność (EV): " + (if (evPct > 0) "+$evPct%" else "$evPct%"),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onExposure(0f) }) { Text("Reset") }
        }
        Slider(value = exposure, onValueChange = onExposure, valueRange = -1f..1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = torch, onClick = onToggleTorch, label = { Text("Lampa") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = grid, onClick = onToggleGrid, label = { Text("Siatka") })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onResetFocus) { Text("Auto-ostrość") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
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
