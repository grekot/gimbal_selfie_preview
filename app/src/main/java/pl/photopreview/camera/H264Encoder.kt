package pl.photopreview.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed by CameraX ImageAnalysis frames (YUV_420_888).
 * Uses the device-independent flexible-YUV input (getInputImage) and drains
 * encoded Annex-B access units synchronously on the analyzer thread.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    bitRate: Int,
    frameRate: Int,
    private val onConfig: (csd: ByteArray) -> Unit,
    private val onEncoded: (nal: ByteArray, keyframe: Boolean) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var running = false
    private var ptsBaseNanos = 0L

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        running = true
    }

    /** Encode one frame (called on the CameraX analyzer thread). */
    fun encode(image: ImageProxy) {
        if (!running) return
        try {
            val inIndex = codec.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val dst = codec.getInputImage(inIndex)
                if (dst != null) copyYuv(image, dst)
                if (ptsBaseNanos == 0L) ptsBaseNanos = System.nanoTime()
                val ptsUs = (System.nanoTime() - ptsBaseNanos) / 1000
                codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, ptsUs, 0)
            }
            drain()
        } catch (_: Throwable) {
            // skip this frame
        }
    }

    private fun drain() {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex < 0) break
            val buf = codec.getOutputBuffer(outIndex)
            if (buf != null && bufferInfo.size > 0) {
                buf.position(bufferInfo.offset)
                buf.limit(bufferInfo.offset + bufferInfo.size)
                val data = ByteArray(bufferInfo.size)
                buf.get(data)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    onConfig(data)
                } else {
                    val keyframe = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onEncoded(data, keyframe)
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    fun release() {
        running = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** Copy a YUV_420_888 ImageProxy into the encoder's flexible-YUV input Image. */
    private fun copyYuv(src: ImageProxy, dst: Image) {
        val sp = src.planes
        val dp = dst.planes
        for (i in 0 until 3) {
            val w = if (i == 0) width else width / 2
            val h = if (i == 0) height else height / 2
            copyPlane(
                sp[i].buffer, sp[i].rowStride, sp[i].pixelStride,
                dp[i].buffer, dp[i].rowStride, dp[i].pixelStride,
                w, h,
            )
        }
    }

    private fun copyPlane(
        src: ByteBuffer, srcRowStride: Int, srcPixStride: Int,
        dst: ByteBuffer, dstRowStride: Int, dstPixStride: Int,
        width: Int, height: Int,
    ) {
        if (srcPixStride == 1 && dstPixStride == 1) {
            val row = ByteArray(width)
            for (r in 0 until height) {
                val sPos = r * srcRowStride
                if (sPos + width > src.capacity()) break
                src.position(sPos)
                src.get(row, 0, width)
                dst.position(r * dstRowStride)
                dst.put(row, 0, width)
            }
        } else {
            for (r in 0 until height) {
                val sRow = r * srcRowStride
                val dRow = r * dstRowStride
                for (c in 0 until width) {
                    val sIdx = sRow + c * srcPixStride
                    val dIdx = dRow + c * dstPixStride
                    if (sIdx < src.capacity() && dIdx < dst.capacity()) {
                        dst.put(dIdx, src.get(sIdx))
                    }
                }
            }
        }
    }
}
