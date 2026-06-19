package pl.photopreview

import android.graphics.Bitmap
import org.json.JSONObject

/** Connection lifecycle state, shared by camera (server) and viewer (client). */
sealed interface SessionStatus {
    data object Idle : SessionStatus
    data class Listening(val port: Int) : SessionStatus
    data class Connecting(val target: String) : SessionStatus
    data class Connected(val peer: String) : SessionStatus
    data class Error(val message: String) : SessionStatus
}

/** Live-preview stream parameters, controllable from the viewer. */
data class StreamConfig(
    val analysisHeight: Int = 720, // 480 or 720
    val fps: Int = 15,
    val jpegQuality: Int = 60,
    val timerSeconds: Int = 0, // self-timer: 0 / 3 / 10
    val saveToViewer: Boolean = true, // also save a full-res copy on the viewer phone
    val useH264: Boolean = true, // H.264 video stream (true) vs per-frame JPEG (false)
    val videoMode: Boolean = false, // camera in video (recording) mode vs photo
    val frontCamera: Boolean = false, // front vs back camera
    val burst: Boolean = false, // one shutter press = 3 photos in a row
    val strongFlash: Boolean = false, // continuous full LED light during capture
) {
    fun toJson(): ByteArray =
        JSONObject()
            .put("h", analysisHeight)
            .put("fps", fps)
            .put("q", jpegQuality)
            .put("t", timerSeconds)
            .put("sv", saveToViewer)
            .put("vc", useH264)
            .put("vm", videoMode)
            .put("fc", frontCamera)
            .put("b", burst)
            .put("sf", strongFlash)
            .toString()
            .toByteArray()

    companion object {
        fun fromJson(bytes: ByteArray): StreamConfig {
            val o = JSONObject(String(bytes))
            return StreamConfig(
                analysisHeight = o.optInt("h", 720),
                fps = o.optInt("fps", 15),
                jpegQuality = o.optInt("q", 60),
                timerSeconds = o.optInt("t", 0),
                saveToViewer = o.optBoolean("sv", true),
                useH264 = o.optBoolean("vc", true),
                videoMode = o.optBoolean("vm", false),
                frontCamera = o.optBoolean("fc", false),
                burst = o.optBoolean("b", false),
                strongFlash = o.optBoolean("sf", false),
            )
        }
    }
}

/** A decoded preview frame ready to draw on the viewer. */
data class DisplayFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int,
    val arrivalNanos: Long,
)

/** H.264 stream init info, sent from camera to viewer before the frames. */
class VideoConfig(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val csd: ByteArray,
) {
    fun toPayload(): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(12 + csd.size)
        bb.putInt(width)
        bb.putInt(height)
        bb.putInt(rotation)
        bb.put(csd)
        return bb.array()
    }

    companion object {
        fun fromPayload(bytes: ByteArray): VideoConfig? {
            if (bytes.size < 12) return null
            val bb = java.nio.ByteBuffer.wrap(bytes)
            val w = bb.getInt()
            val h = bb.getInt()
            val rot = bb.getInt()
            val csd = ByteArray(bb.remaining())
            bb.get(csd)
            return VideoConfig(w, h, rot, csd)
        }
    }
}
