package pl.photopreview.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import pl.photopreview.VideoConfig
import java.nio.ByteBuffer

/**
 * Hardware H.264 decoder rendering directly to a [Surface]. Rotation is applied by the
 * decoder via KEY_ROTATION. Frames are fed (in order) from the network reader thread.
 */
class H264Decoder(surface: Surface, config: VideoConfig) {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var running = false
    @Volatile private var sawKeyframe = false

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.width.coerceAtLeast(1),
            config.height.coerceAtLeast(1),
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.csd))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_ROTATION, ((config.rotation % 360) + 360) % 360)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
            }
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        running = true
    }

    fun feed(nal: ByteArray, keyframe: Boolean) {
        if (!running) return
        if (!sawKeyframe) {
            if (!keyframe) return // wait for the first keyframe to start cleanly
            sawKeyframe = true
        }
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val input = codec.getInputBuffer(inIndex)
                if (input != null) {
                    input.clear()
                    input.put(nal)
                    codec.queueInputBuffer(inIndex, 0, nal.size, System.nanoTime() / 1000, 0)
                }
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outIndex < 0) break
                codec.releaseOutputBuffer(outIndex, true) // render to surface
            }
        } catch (_: Throwable) {
            running = false
        }
    }

    fun release() {
        running = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
