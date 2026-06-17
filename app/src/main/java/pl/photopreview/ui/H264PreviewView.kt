package pl.photopreview.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import pl.photopreview.VideoConfig
import pl.photopreview.video.H264Decoder

/**
 * Renders the H.264 stream to a SurfaceView via [H264Decoder]. The decoder is (re)created
 * whenever the Surface or the stream's [VideoConfig] changes, and is fed via [registerSink]
 * (the viewer session pushes NAL units to the registered callback on its reader thread).
 */
@Composable
fun H264PreviewView(
    videoConfig: VideoConfig?,
    registerSink: (((ByteArray, Boolean) -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var surface by remember { mutableStateOf<Surface?>(null) }
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    surface = holder.surface
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
            })
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cfg = videoConfig
        val viewMod = if (cfg != null && cfg.width > 0 && cfg.height > 0) {
            val rot = ((cfg.rotation % 360) + 360) % 360
            val rotated = rot == 90 || rot == 270
            val w = if (rotated) cfg.height else cfg.width
            val h = if (rotated) cfg.width else cfg.height
            Modifier.aspectRatio(w.toFloat() / h.toFloat())
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(factory = { surfaceView }, modifier = viewMod)
    }

    DisposableEffect(surface, videoConfig) {
        var decoder: H264Decoder? = null
        val s = surface
        val cfg = videoConfig
        if (s != null && cfg != null) {
            decoder = runCatching { H264Decoder(s, cfg) }.getOrNull()
            val d = decoder
            val sink: ((ByteArray, Boolean) -> Unit)? =
                if (d != null) { nal, keyframe -> d.feed(nal, keyframe) } else null
            registerSink(sink)
        }
        onDispose {
            registerSink(null)
            decoder?.release()
        }
    }
}
